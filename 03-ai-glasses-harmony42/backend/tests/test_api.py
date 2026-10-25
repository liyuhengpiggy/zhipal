import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app import ai_service, main


@pytest.fixture(autouse=True)
def isolated_config(monkeypatch):
    monkeypatch.setenv("AI_MODE", "mock")
    monkeypatch.setenv("AI_PROVIDER", "openai_compatible")
    monkeypatch.setenv("AI_OUTPUT_FORMAT", "json_schema")
    monkeypatch.setenv("AI_TIMEOUT_SECONDS", "25")
    monkeypatch.delenv("AI_API_KEY", raising=False)


def png_bytes() -> bytes:
    return b"\x89PNG\r\n\x1a\n" + b"test image"


def jpeg_bytes() -> bytes:
    return b"\xff\xd8\xff" + b"test image"


def test_health():
    response = TestClient(main.app).get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_upload_jpeg_saves_image(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    content = jpeg_bytes()
    response = TestClient(main.app).post(
        "/upload", files={"file": ("test.jpg", content, "image/jpeg")}
    )
    assert response.status_code == 200
    result = response.json()
    assert result["success"] is True
    assert result["size"] == len(content)
    assert (tmp_path / result["filename"]).read_bytes() == content


def test_upload_rejects_empty_wrong_type_and_oversize():
    client = TestClient(main.app)
    assert client.post("/upload", files={"file": ("empty.png", b"", "image/png")}).status_code == 400
    assert client.post("/upload", files={"file": ("note.txt", b"text", "text/plain")}).status_code == 415
    too_large = b"\x89PNG\r\n\x1a\n" + b"x" * main.MAX_IMAGE_BYTES
    assert client.post("/upload", files={"file": ("large.png", too_large, "image/png")}).status_code == 413


def test_analyze_mock_mode_and_local_image(monkeypatch):
    monkeypatch.setenv("AI_MODE", "mock")
    response = TestClient(main.app).post(
        "/analyze",
        files={"image": ("scene.png", png_bytes(), "image/png")},
        data={"intent": "帮我打开微信"},
    )
    assert response.status_code == 200
    assert response.headers["X-AI-Mode"] == "mock"
    assert response.json() == {
        "action": "open_app",
        "app_name": "微信",
        "target": None,
        "confirmation_required": False,
        "message": "我来帮您打开微信。",
    }


def test_mock_returns_unknown_for_unsupported_intent(monkeypatch):
    monkeypatch.setenv("AI_MODE", "mock")
    response = TestClient(main.app).post(
        "/analyze",
        files={"image": ("scene.jpg", jpeg_bytes(), "image/jpeg")},
        data={"intent": "帮我转账五千元"},
    )
    assert response.status_code == 200
    assert response.json()["action"] == "unknown"
    assert response.json()["app_name"] is None


def test_openai_compatible_sends_original_image_and_intent(monkeypatch):
    monkeypatch.setenv("AI_MODE", "openai_compatible")
    monkeypatch.setenv("AI_API_KEY", "test-key")
    monkeypatch.setenv("AI_BASE_URL", "https://model.example/v1")
    monkeypatch.setenv("AI_MODEL", "vision-model")
    captured = {}

    async def fake_post(self, url, **kwargs):
        captured["url"] = url
        captured["body"] = kwargs["json"]
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps({
                "action": "open_app",
                "app_name": "微信",
                "target": None,
                "confirmation_required": False,
                "message": "我来帮您打开微信。",
            }, ensure_ascii=False)}}]},
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    response = TestClient(main.app).post(
        "/analyze",
        files={"image": ("scene.jpg", jpeg_bytes(), "image/jpeg")},
        data={"intent": "帮我打开微信"},
    )
    assert response.status_code == 200
    assert captured["url"] == "https://model.example/v1/chat/completions"
    user_content = captured["body"]["messages"][1]["content"]
    assert {"type": "text", "text": "用户意图：帮我打开微信"} in user_content
    image = next(item for item in user_content if item["type"] == "image_url")
    assert image["image_url"]["url"].startswith("data:image/jpeg;base64,")
    assert captured["body"]["response_format"]["type"] == "json_schema"


def test_invalid_model_json_safely_degrades_to_unknown(monkeypatch):
    monkeypatch.setenv("AI_MODE", "openai_compatible")
    monkeypatch.setenv("AI_API_KEY", "test-key")

    async def fake_post(self, url, **kwargs):
        return httpx.Response(200, json={"choices": [{"message": {"content": "not json"}}]})

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    response = TestClient(main.app).post(
        "/analyze", files={"image": ("a.png", png_bytes(), "image/png")}, data={"intent": "打开微信"}
    )
    assert response.status_code == 200
    assert response.json()["action"] == "unknown"


def test_auth_error_is_clear_and_does_not_crash(monkeypatch):
    monkeypatch.setenv("AI_MODE", "openai_compatible")
    monkeypatch.setenv("AI_API_KEY", "bad-key")

    async def fake_post(self, url, **kwargs):
        return httpx.Response(401, json={"error": "unauthorized"})

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    response = TestClient(main.app).post(
        "/analyze", files={"image": ("a.png", png_bytes(), "image/png")}, data={"intent": "打开微信"}
    )
    assert response.status_code == 502
    assert "鉴权失败" in response.json()["detail"]


def test_network_error_is_reported(monkeypatch):
    monkeypatch.setenv("AI_MODE", "openai_compatible")
    monkeypatch.setenv("AI_API_KEY", "test-key")

    async def fake_post(self, url, **kwargs):
        raise httpx.ReadTimeout("simulated timeout")

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    response = TestClient(main.app).post(
        "/analyze", files={"image": ("a.png", png_bytes(), "image/png")}, data={"intent": "打开微信"}
    )
    assert response.status_code == 502
    assert "超时" in response.json()["detail"]


def test_openai_mode_requires_api_key(monkeypatch):
    monkeypatch.setenv("AI_MODE", "openai_compatible")
    monkeypatch.delenv("AI_API_KEY", raising=False)
    response = TestClient(main.app).post(
        "/analyze", files={"image": ("a.jpg", jpeg_bytes(), "image/jpeg")}, data={"intent": "打开微信"}
    )
    assert response.status_code == 502
    assert "AI_API_KEY" in response.json()["detail"]


@pytest.mark.parametrize("payload", [
    {}, {"choices": []}, {"choices": [{"message": {"content": None}}]},
    {"choices": [{"message": {"content": '{"action":"delete_all"}'}}]},
    {"choices": [{"message": {"content": '{"action":"open_app","app_name":null,"target":null,"confirmation_required":"false","message":"hi"}'}}]},
])
def test_bad_model_structures_degrade_and_service_survives(monkeypatch, payload):
    monkeypatch.setenv("AI_MODE", "openai_compatible")
    monkeypatch.setenv("AI_API_KEY", "test-key")

    async def fake_post(self, url, **kwargs):
        return httpx.Response(200, json=payload)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    client = TestClient(main.app)
    response = client.post("/analyze", files={"image": ("a.jpg", jpeg_bytes(), "image/jpeg")}, data={"intent": "打开微信"})
    assert response.status_code == 200
    assert response.json()["action"] == "unknown"
    assert client.get("/health").status_code == 200


@pytest.mark.parametrize("intent", ["不要打开微信", "打开微信并转账", "打开微信还是支付宝"])
def test_mock_does_not_treat_ambiguous_or_other_operations_as_open(monkeypatch, intent):
    response = TestClient(main.app).post("/analyze", files={"image": ("a.jpg", jpeg_bytes(), "image/jpeg")}, data={"intent": intent})
    assert response.json()["action"] == "unknown"


def test_analyze_validates_input():
    client = TestClient(main.app)
    assert client.post("/analyze", files={"image": ("a.png", b"not png", "image/png")}, data={"intent": "打开微信"}).status_code == 415
    assert client.post("/analyze", files={"image": ("a.png", b"", "image/png")}, data={"intent": "打开微信"}).status_code == 400
    assert client.post("/analyze", files={"image": ("a.png", png_bytes() + b"x" * main.MAX_IMAGE_BYTES, "image/png")}, data={"intent": "打开微信"}).status_code == 413
    assert client.post("/analyze", files={"image": ("a.png", png_bytes(), "image/png")}).status_code == 422
