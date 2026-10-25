import io
import json
import unittest
from unittest.mock import Mock, patch
import urllib.error

from app.providers.base import ProviderFailure
from app.providers.openai_responses import MAX_PROVIDER_RESPONSE_BYTES, NoRedirect, StdlibJsonTransport


class FakeResponse(io.BytesIO):
    def __enter__(self): return self
    def __exit__(self, *args): self.close()


class TransportTests(unittest.TestCase):
    def post(self, opener):
        with patch("urllib.request.build_opener", return_value=opener):
            return StdlibJsonTransport().post("https://api.openai.com/v1/responses",
                                               {"Authorization": "Bearer placeholder", "Content-Type": "application/json"},
                                               {"store": False, "input": "不可信页面"}, 15)

    def test_serialization_and_one_http_open(self):
        opener = Mock()
        opener.open.return_value = FakeResponse(b'{"status":"completed"}')
        self.assertEqual(self.post(opener), {"status": "completed"})
        opener.open.assert_called_once()
        request = opener.open.call_args.args[0]
        self.assertEqual(request.method, "POST")
        self.assertEqual(json.loads(request.data)["input"], "不可信页面")
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 15)

    def test_http_error_body_not_exposed_or_retried(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://api.openai.com/v1/responses", 401,
                                                         "private-body", {}, io.BytesIO(b"secret-key"))
        with self.assertRaises(ProviderFailure) as captured:
            self.post(opener)
        self.assertEqual(str(captured.exception), "provider_http_error")
        opener.open.assert_called_once()

    def test_timeout_and_network_error_not_retried(self):
        for error in [TimeoutError("private"), urllib.error.URLError("private")]:
            opener = Mock()
            opener.open.side_effect = error
            with self.assertRaises(ProviderFailure) as captured: self.post(opener)
            self.assertNotIn("private", str(captured.exception))
            opener.open.assert_called_once()

    def test_oversized_or_invalid_response(self):
        for body in [b"x" * (MAX_PROVIDER_RESPONSE_BYTES + 1), b"[]", b"not-json", b'{"x":1,"x":2}']:
            opener = Mock()
            opener.open.return_value = FakeResponse(body)
            with self.assertRaises(ProviderFailure): self.post(opener)
            opener.open.assert_called_once()

    def test_redirects_are_not_followed(self):
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://other.example"))
