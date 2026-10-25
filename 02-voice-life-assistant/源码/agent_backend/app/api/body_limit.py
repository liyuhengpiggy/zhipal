"""ASGI request limit applied before JSON parsing or Pydantic validation."""

from ..schemas.observation import MAX_REQUEST_BYTES


class BodyLimitMiddleware:
    def __init__(self, app, limit: int = MAX_REQUEST_BYTES):
        self.app = app
        self.limit = limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("method") != "POST":
            await self.app(scope, receive, send)
            return
        content_lengths = [value for key, value in scope.get("headers", []) if key.lower() == b"content-length"]
        try:
            if len(content_lengths) > 1 or (content_lengths and not 0 <= int(content_lengths[0]) <= self.limit):
                await self._reject(send)
                return
        except ValueError:
            await self._reject(send)
            return
        buffered = bytearray()
        while True:
            event = await receive()
            if event["type"] == "http.disconnect":
                return
            if event["type"] != "http.request":
                continue
            chunk = event.get("body", b"")
            if len(buffered) + len(chunk) > self.limit:
                await self._reject(send)
                return
            buffered.extend(chunk)
            if not event.get("more_body", False):
                break

        delivered = False

        async def limited_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(buffered), "more_body": False}
            return await receive()

        await self.app(scope, limited_receive, send)

    @staticmethod
    async def _reject(send):
        payload = b'{"detail":"request_body_too_large_or_invalid"}'
        await send({"type": "http.response.start", "status": 413,
                    "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(payload)).encode())]})
        await send({"type": "http.response.body", "body": payload})
