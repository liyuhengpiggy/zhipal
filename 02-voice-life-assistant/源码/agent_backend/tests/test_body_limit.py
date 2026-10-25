import unittest

from app.api.body_limit import BodyLimitMiddleware


class BodyLimitTests(unittest.IsolatedAsyncioTestCase):
    async def run_request(self, headers, chunks, limit=8):
        calls = []
        sent = []
        incoming = [{"type": "http.request", "body": chunk, "more_body": index < len(chunks) - 1}
                    for index, chunk in enumerate(chunks)]

        async def receive():
            return incoming.pop(0) if incoming else {"type": "http.disconnect"}

        async def send(event): sent.append(event)

        async def application(scope, receive, send):
            calls.append(scope)
            bodies = []
            while True:
                event = await receive()
                bodies.append(event["body"])
                if not event.get("more_body", False): break
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": b"".join(bodies)})

        await BodyLimitMiddleware(application, limit)({"type": "http", "method": "POST", "headers": headers}, receive, send)
        return calls, sent

    async def test_content_length_rejects_before_app(self):
        calls, sent = await self.run_request([(b"content-length", b"9")], [b"123"])
        self.assertEqual(calls, [])
        self.assertEqual(sent[0]["status"], 413)

    async def test_streaming_chunk_limit_not_trusted_header(self):
        calls, sent = await self.run_request([(b"content-length", b"1")], [b"12345", b"6789"])
        self.assertEqual(calls, [])
        self.assertEqual(sent[0]["status"], 413)

    async def test_exact_limit_preserves_request(self):
        calls, sent = await self.run_request([], [b"1234", b"5678"])
        self.assertEqual(len(calls), 1)
        self.assertEqual(sent[-1]["body"], b"12345678")

    async def test_invalid_content_length_rejected(self):
        calls, sent = await self.run_request([(b"content-length", b"wrong")], [b"123"])
        self.assertEqual(calls, [])
        self.assertEqual(sent[0]["status"], 413)
