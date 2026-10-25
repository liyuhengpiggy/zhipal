import asyncio
import base64
import json
from tempfile import SpooledTemporaryFile

import httpx
import pytest
from fastapi import HTTPException, UploadFile
from fastapi.testclient import TestClient
from starlette.datastructures import Headers

from app import main
from app.agent_coordinator import CoordinationContext
from app.vision_service import VisionObservation

IMAGE = b"\xff\xd8\xfftest"
OBSERVATION = {
    "scene": "手机界面", "visible_elements": ["微信图标"],
    "current_state": "手机显示应用列表。", "possible_next_steps": ["可能想查找应用"],
    "suggested_assistance": ["可以询问用户是否需要帮助。"], "confidence": 0.8,
}


@pytest.fixture(autouse=True)
def isolated(monkeypatch):
    import os
    for name in list(os.environ):
        if name.startswith(("VISION_", "ARK_", "AI_")):
            monkeypatch.delenv(name)
    monkeypatch.setenv("AI_MODE", "mock")


def request():
    return TestClient(main.app).post("/vision/analyze", files={"image": ("a.jpg", IMAGE, "image/jpeg")})


def cloud(monkeypatch, payload=OBSERVATION, status=200, exception=None):
    monkeypatch.setenv("VISION_MODE", "ark")
    monkeypatch.setenv("ARK_API_KEY", "fake-test-key")
    monkeypatch.setenv("ARK_MODEL", "fake-vision-model")
    captured = {}

    async def post(self, url, **kwargs):
        captured.update(url=url, body=kwargs["json"])
        if exception:
            raise exception
        return httpx.Response(status, json={"choices": [{"message": {"content": json.dumps(payload)}}]})

    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    return captured


def test_mock_no_intent_no_disk_write(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    result = request()
    assert result.status_code == 200
    assert result.headers["X-AI-Mode"] == "mock"
    assert result.json()["status"] == "mock"
    assert result.json()["analysis"] is None
    assert not list(tmp_path.iterdir())


def test_cloud_original_image_and_no_user_intent(monkeypatch):
    captured = cloud(monkeypatch)
    response = request()
    assert response.status_code == 200
    assert response.json()["analysis"] == OBSERVATION
    assert captured["url"] == "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    content = captured["body"]["messages"][1]["content"]
    assert content[1]["image_url"]["url"] == "data:image/jpeg;base64," + base64.b64encode(IMAGE).decode()
    assert captured["body"]["response_format"]["type"] == "json_object"


@pytest.mark.parametrize("payload", [{}, {**OBSERVATION, "confidence": 2},
    {**OBSERVATION, "confidence": "0.9"}, {**OBSERVATION, "action": "open_app"}, "not json"])
def test_bad_output_never_becomes_observation(monkeypatch, payload):
    cloud(monkeypatch, payload=payload)
    response = request()
    assert response.status_code == 502
    assert response.json()["analysis"] is None
    assert response.json()["error"]["code"] == "invalid_model_output"
    assert TestClient(main.app).get("/health").status_code == 200


@pytest.mark.parametrize("status,exception,expected,code", [
    (401, None, 502, "authentication_failed"),
    (403, None, 502, "authentication_failed"),
    (500, None, 502, "upstream_http_error"),
    (200, httpx.ReadTimeout("private upstream body"), 504, "timeout"),
    (200, httpx.ConnectError("private upstream body"), 502, "connection_failed"),
])
def test_cloud_errors_safe(monkeypatch, status, exception, expected, code, caplog):
    cloud(monkeypatch, status=status, exception=exception)
    response = request()
    assert response.status_code == expected
    assert response.json()["error"]["code"] == code
    assert response.json()["analysis"] is None
    assert "private upstream body" not in response.text + caplog.text
    assert "fake-test-key" not in caplog.text


def test_unrecognized(monkeypatch):
    cloud(monkeypatch, payload={**OBSERVATION, "scene": "unknown"})
    result = request()
    assert result.status_code == 422
    assert result.json()["status"] == "unrecognized"
    assert result.json()["analysis"] is None


def test_no_key_never_silently_mocks(monkeypatch):
    monkeypatch.setenv("VISION_MODE", "ark")
    response = request()
    assert response.status_code == 503
    assert response.json()["status"] == "error"


@pytest.mark.parametrize("data,mime,error", [
    (IMAGE, "image/jpeg", None), (b"", "image/jpeg", 400),
    (IMAGE, "text/plain", 415), (b"bad", "image/png", 415),
    (IMAGE + b"x" * main.MAX_IMAGE_BYTES, "image/jpeg", 413),
], ids=["success", "empty", "wrong-mime", "wrong-signature", "oversize"])
def test_rolled_tempfile_closed_on_all_validation_paths(data, mime, error):
    spool = SpooledTemporaryFile(max_size=1)
    spool.write(data)
    spool.rollover()
    spool.seek(0)
    upload = UploadFile(spool, headers=Headers({"content-type": mime}))
    if error:
        with pytest.raises(HTTPException) as exc:
            asyncio.run(main.read_valid_image(upload))
        assert exc.value.status_code == error
    else:
        assert asyncio.run(main.read_valid_image(upload))[0] == data
    assert spool.closed


def test_cleanup_before_upstream_failure(monkeypatch):
    import app.main as routes
    original = routes.read_valid_image
    uploads = []

    async def capture(upload):
        uploads.append(upload)
        return await original(upload)

    monkeypatch.setattr(routes, "read_valid_image", capture)
    cloud(monkeypatch, exception=httpx.ReadTimeout("test"))
    assert request().status_code == 504
    assert uploads[0].file.closed


def test_coordination_only_returns_suggestions():
    context = CoordinationContext(vision_observation=VisionObservation(**OBSERVATION))
    assert context.explicit_user_intent is None
    assert context.suggestions_only() == OBSERVATION["suggested_assistance"]


def test_endpoint_contract():
    schema = TestClient(main.app).get("/openapi.json").json()
    body = schema["paths"]["/vision/analyze"]["post"]["requestBody"]["content"]["multipart/form-data"]["schema"]
    fields = schema["components"]["schemas"][body["$ref"].split("/")[-1]]
    assert fields["required"] == ["image"]
    assert set(fields["properties"]) == {"image"}
