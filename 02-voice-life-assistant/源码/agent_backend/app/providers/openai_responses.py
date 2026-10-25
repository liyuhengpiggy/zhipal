"""One HTTPS Responses request using the standard library; no SDK retries."""

import asyncio
import json
import socket
from typing import Protocol
import urllib.error
import urllib.request

from ..schemas.observation import ObservationRequest
from ..services.context_builder import ContextBuilder
from ..services.response_validator import reject_duplicate_keys
from .base import ProviderFailure, VisionModelProvider

OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
MAX_PROVIDER_RESPONSE_BYTES = 256 * 1024


class JsonTransport(Protocol):
    def post(self, url: str, headers: dict, body: dict, timeout_seconds: float) -> dict: ...


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class StdlibJsonTransport:
    # Explicit counterpart of SDK max_retries=0. urllib never retries here.
    max_retries = 0

    def post(self, url: str, headers: dict, body: dict, timeout_seconds: float) -> dict:
        request = urllib.request.Request(
            url, data=json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            headers=headers, method="POST",
        )
        try:
            # Reject redirects so a key cannot be forwarded to another host.
            opener = urllib.request.build_opener(NoRedirect())
            with opener.open(request, timeout=timeout_seconds) as response:
                payload = response.read(MAX_PROVIDER_RESPONSE_BYTES + 1)
                if len(payload) > MAX_PROVIDER_RESPONSE_BYTES:
                    raise ProviderFailure("provider_response_too_large")
                value = json.loads(payload, object_pairs_hook=reject_duplicate_keys)
                if not isinstance(value, dict):
                    raise ProviderFailure("provider_invalid_response")
                return value
        except ProviderFailure:
            raise
        except (TimeoutError, socket.timeout):
            raise ProviderFailure("provider_timeout") from None
        except urllib.error.HTTPError:
            # Upstream bodies and headers may contain private content.
            raise ProviderFailure("provider_http_error") from None
        except urllib.error.URLError:
            raise ProviderFailure("provider_connection_error") from None
        except (ValueError, UnicodeError):
            raise ProviderFailure("provider_invalid_response") from None


class OpenAIResponsesProvider(VisionModelProvider):
    max_retries = 0

    def __init__(self, model: str, api_key: str, transport: JsonTransport | None = None,
                 context_builder: ContextBuilder | None = None):
        if not model.strip() or not api_key.strip():
            raise ValueError("model and server-side API key are required")
        self._model = model
        self._api_key = api_key
        self._transport = transport or StdlibJsonTransport()
        self._context_builder = context_builder or ContextBuilder()

    async def predict(self, observation: ObservationRequest, timeout_seconds: float) -> str:
        context = self._context_builder.build(observation)
        body = {
            "model": self._model,
            "store": False,
            "instructions": context["system"],
            "input": [{"role": "user", "content": [
                {"type": "input_text", "text": context["context_json"]},
                {"type": "input_image", "image_url": context["image_url"], "detail": "auto"},
            ]}],
            "text": {"format": {
                "type": "json_schema", "name": "next_action_prediction", "strict": True,
                "schema": self._context_builder.response_schema(observation),
            }},
            "max_output_tokens": 1800,
        }
        response = await asyncio.to_thread(
            self._transport.post, OPENAI_RESPONSES_URL,
            {"Authorization": "Bearer " + self._api_key, "Content-Type": "application/json"},
            body, timeout_seconds,
        )
        if response.get("status") != "completed":
            raise ProviderFailure("provider_not_completed")
        texts = []
        output = response.get("output")
        if not isinstance(output, list):
            raise ProviderFailure("provider_invalid_response")
        for item in output:
            if not isinstance(item, dict):
                raise ProviderFailure("provider_invalid_response")
            if item.get("type") not in {"message", "reasoning"}:
                raise ProviderFailure("provider_unexpected_tool")
            if item.get("type") != "message":
                continue
            content = item.get("content", [])
            if not isinstance(content, list):
                raise ProviderFailure("provider_invalid_response")
            for part in content:
                if not isinstance(part, dict):
                    raise ProviderFailure("provider_invalid_response")
                if part.get("type") == "refusal":
                    raise ProviderFailure("provider_refused")
                if part.get("type") == "output_text":
                    text = part.get("text")
                    if not isinstance(text, str):
                        raise ProviderFailure("provider_invalid_response")
                    texts.append(text)
        if len(texts) != 1:
            raise ProviderFailure("provider_invalid_response")
        return texts[0]
