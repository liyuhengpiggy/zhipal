import asyncio
import json
import unittest

from app.config import Settings
from app.providers.base import ProviderFailure, VisionModelProvider
from app.providers.mock import MockVisionProvider
from app.providers.openai_responses import OpenAIResponsesProvider, StdlibJsonTransport
from app.services.context_builder import ContextBuilder, SYSTEM_PROMPT
from app.services.predictor import NextActionPredictor
from app.services.replay_guard import ReplayGuard
from fixtures import observation, prediction


class RecordingTransport:
    def __init__(self, response=None, error=None):
        self.calls = []
        self.response = response
        self.error = error

    def post(self, url, headers, body, timeout_seconds):
        self.calls.append((url, headers, body, timeout_seconds))
        if self.error:
            raise self.error
        return self.response


def envelope(value=None):
    return {"status": "completed", "output": [{"type": "message", "content": [
        {"type": "output_text", "text": json.dumps(value or prediction())}]}]}


class CountingProvider(VisionModelProvider):
    def __init__(self, result=None, delay=0, error=None, ignore_cancellation=False):
        self.calls = 0
        self.result = result or prediction()
        self.delay = delay
        self.error = error
        self.ignore_cancellation = ignore_cancellation

    async def predict(self, observation, timeout_seconds):
        self.calls += 1
        try:
            await asyncio.sleep(self.delay)
        except asyncio.CancelledError:
            if not self.ignore_cancellation: raise
            await asyncio.sleep(0.01)
        if self.error: raise self.error
        return self.result


class PredictorTests(unittest.IsolatedAsyncioTestCase):
    async def test_mock_always_noop(self):
        result = await NextActionPredictor(MockVisionProvider()).predict(observation())
        self.assertEqual((result.decision.action, result.decision.candidate_id), ("NO_OP", "none-1"))

    async def test_single_call_for_success(self):
        provider = CountingProvider()
        result = await NextActionPredictor(provider).predict(observation())
        self.assertEqual(result.decision.action, "TAP")
        self.assertEqual(provider.calls, 1)

    async def test_timeout_has_no_late_action_or_retry(self):
        provider = CountingProvider(delay=1, ignore_cancellation=True)
        result = await NextActionPredictor(provider, timeout_seconds=0.01).predict(observation())
        self.assertEqual(result.decision.action, "NO_OP")
        await asyncio.sleep(0.04)
        self.assertEqual(result.decision.action, "NO_OP")
        self.assertEqual(provider.calls, 1)

    async def test_cancel_does_not_return_prediction(self):
        provider = CountingProvider(delay=1)
        task = asyncio.create_task(NextActionPredictor(provider).predict(observation()))
        await asyncio.sleep(0.01)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        self.assertEqual(provider.calls, 1)

    async def test_all_provider_failures_noop_once(self):
        for code in ("provider_timeout", "provider_refused", "provider_http_error", "provider_not_completed"):
            provider = CountingProvider(error=ProviderFailure(code))
            result = await NextActionPredictor(provider).predict(observation())
            self.assertEqual(result.decision.action, "NO_OP")
            self.assertEqual(provider.calls, 1)

    async def test_invalid_result_noop_once(self):
        for result in [prediction("missing"), prediction("tap-1", "BACK"), {"bad": "data"}, "garbage"]:
            provider = CountingProvider(result=result)
            response = await NextActionPredictor(provider).predict(observation())
            self.assertEqual(response.decision.action, "NO_OP")
            self.assertEqual(provider.calls, 1)

    async def test_debug_log_has_no_context_or_model_reason(self):
        with self.assertLogs("next_action", "INFO") as captured:
            await NextActionPredictor(CountingProvider(), debug_logging=True).predict(observation())
        output = " ".join(captured.output)
        self.assertIn("candidate_count", output)
        for private in ("继续查看", "下一页", "example.practice", "/9j/"):
            self.assertNotIn(private, output)

    async def test_unexpected_exception_not_logged(self):
        provider = CountingProvider(error=RuntimeError("secret-key-private-screenshot"))
        with self.assertLogs("next_action", "INFO") as captured:
            result = await NextActionPredictor(provider).predict(observation())
        self.assertEqual(result.decision.action, "NO_OP")
        self.assertNotIn("secret-key", " ".join(captured.output))


class ResponsesProviderTests(unittest.IsolatedAsyncioTestCase):
    async def test_responses_wire_format_dynamic_schema_and_one_call(self):
        transport = RecordingTransport(envelope())
        provider = OpenAIResponsesProvider("configured-vision-model", "test-placeholder", transport)
        result = await NextActionPredictor(provider).predict(observation())
        self.assertEqual(result.decision.action, "TAP")
        self.assertEqual(len(transport.calls), 1)
        url, headers, body, timeout = transport.calls[0]
        self.assertEqual(url, "https://api.openai.com/v1/responses")
        self.assertEqual(headers["Authorization"], "Bearer test-placeholder")
        self.assertFalse(body["store"])
        self.assertNotIn("tools", body)
        self.assertNotIn("response_format", body)
        self.assertEqual(body["text"]["format"]["type"], "json_schema")
        self.assertTrue(body["text"]["format"]["strict"])
        self.assertEqual(body["model"], "configured-vision-model")
        schema = body["text"]["format"]["schema"]
        self.assertEqual(schema["properties"]["request_id"]["enum"], [observation().request_id])
        self.assertEqual(schema["properties"]["decision"]["properties"]["candidate_id"]["enum"],
                         [item.candidate_id for item in observation().candidates])
        content = body["input"][0]["content"]
        self.assertEqual(content[1]["type"], "input_image")
        self.assertTrue(content[1]["image_url"].startswith("data:image/jpeg;base64,"))
        self.assertEqual(timeout, 15)
        self.assertEqual(provider.max_retries, 0)
        self.assertEqual(StdlibJsonTransport.max_retries, 0)

    async def test_refusal_is_safe_noop(self):
        response = {"status": "completed", "output": [{"type": "message", "content": [{"type": "refusal", "refusal": "private"}]}]}
        transport = RecordingTransport(response)
        result = await NextActionPredictor(OpenAIResponsesProvider("test", "placeholder", transport)).predict(observation())
        self.assertEqual(result.decision.action, "NO_OP")
        self.assertEqual(len(transport.calls), 1)

    async def test_incomplete_malformed_and_tool_outputs_rejected(self):
        for response in [{"status": "incomplete", "output": []}, {"status": "completed", "output": "wrong"},
                         {"status": "completed", "output": [{"type": "function_call"}]},
                         {"status": "completed", "output": []}]:
            transport = RecordingTransport(response)
            result = await NextActionPredictor(OpenAIResponsesProvider("test", "placeholder", transport)).predict(observation())
            self.assertEqual(result.decision.action, "NO_OP")
            self.assertEqual(len(transport.calls), 1)

    async def test_http_failure_no_retry(self):
        transport = RecordingTransport(error=ProviderFailure("provider_http_error"))
        result = await NextActionPredictor(OpenAIResponsesProvider("test", "placeholder", transport)).predict(observation())
        self.assertEqual(result.decision.action, "NO_OP")
        self.assertEqual(len(transport.calls), 1)


class ConfigAndContextTests(unittest.TestCase):
    def test_openai_requires_model_and_secret(self):
        with self.assertRaises(ValueError): Settings()
        with self.assertRaises(ValueError): Settings(model="test")
        self.assertNotIn("placeholder", repr(Settings(model="test", api_key="placeholder", api_token="placeholder")))

    def test_mock_needs_no_secret(self):
        self.assertEqual(Settings(provider="mock").provider, "mock")

    def test_context_has_untrusted_rule_and_no_image_copy(self):
        context = ContextBuilder().build(observation())
        self.assertIn("不可信", SYSTEM_PROMPT)
        parsed = json.loads(context["context_json"])
        self.assertNotIn("screenshot", parsed)
        self.assertEqual(len(parsed["candidates"]), 4)

    def test_replay_guard_rejects_duplicate_and_full(self):
        guard = ReplayGuard(capacity=1)
        self.assertTrue(guard.claim("id-1"))
        self.assertFalse(guard.claim("id-1"))
        self.assertFalse(guard.claim("id-2"))
