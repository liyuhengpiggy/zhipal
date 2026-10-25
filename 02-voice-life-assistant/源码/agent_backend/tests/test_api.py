"""Real FastAPI ASGI tests; explicitly skipped if optional runtime is unavailable."""

import importlib.util
import json
import unittest
from unittest.mock import patch

from app.config import Settings
from fixtures import payload

HAS_FASTAPI = importlib.util.find_spec("fastapi") is not None
if HAS_FASTAPI:
    with patch.dict("os.environ", {"VISION_PROVIDER": "mock"}):
        from app.main import create_app


@unittest.skipUnless(HAS_FASTAPI, "FastAPI is not installed; HTTP route integration unverified")
class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def request(self, application, path, body=b"", headers=None, method="POST"):
        events = []
        received = False

        async def receive():
            nonlocal received
            if not received:
                received = True
                return {"type": "http.request", "body": body, "more_body": False}
            return {"type": "http.disconnect"}

        async def send(event): events.append(event)

        await application({"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
                           "scheme": "https", "method": method, "path": path, "raw_path": path.encode(),
                           "root_path": "", "query_string": b"", "headers": headers or [],
                           "client": ("127.0.0.1", 1000), "server": ("test", 443)}, receive, send)
        status = next(item["status"] for item in events if item["type"] == "http.response.start")
        response = b"".join(item.get("body", b"") for item in events if item["type"] == "http.response.body")
        return status, json.loads(response)

    async def test_health(self):
        status, result = await self.request(create_app(Settings(provider="mock")), "/health", method="GET")
        self.assertEqual(status, 200)
        self.assertTrue(result["ok"])

    async def test_mock_predict_and_duplicate_rejection(self):
        application = create_app(Settings(provider="mock"))
        args = (application, "/api/v1/next-action/predict", json.dumps(payload()).encode(), [(b"content-type", b"application/json")])
        status, result = await self.request(*args)
        self.assertEqual(status, 200)
        self.assertEqual(result["decision"]["action"], "NO_OP")
        status, _ = await self.request(*args)
        self.assertEqual(status, 409)

    async def test_optional_token_rejects_unauthorized(self):
        application = create_app(Settings(provider="mock", api_token="test-placeholder"))
        body = json.dumps(payload()).encode()
        status, _ = await self.request(application, "/api/v1/next-action/predict", body, [(b"content-type", b"application/json")])
        self.assertEqual(status, 401)

    async def test_validation_error_does_not_echo_private_input(self):
        value = payload()
        value["private-field"] = "private-screen-content"
        status, result = await self.request(create_app(Settings(provider="mock")), "/api/v1/next-action/predict",
                                            json.dumps(value).encode(), [(b"content-type", b"application/json")])
        self.assertEqual(status, 422)
        self.assertNotIn("private-screen-content", json.dumps(result))

    async def test_request_body_limit(self):
        status, _ = await self.request(create_app(Settings(provider="mock")), "/api/v1/next-action/predict", b"x" * (2 * 1024 * 1024 + 1))
        self.assertEqual(status, 413)

    async def test_duplicate_json_and_content_type_rejected(self):
        application = create_app(Settings(provider="mock"))
        status, _ = await self.request(application, "/api/v1/next-action/predict", b'{"schema_version":1,"schema_version":1}',
                                       [(b"content-type", b"application/json")])
        self.assertEqual(status, 422)
        status, _ = await self.request(application, "/api/v1/next-action/predict", b"{}")
        self.assertEqual(status, 415)
