import httpx
import pytest
from fastapi.testclient import TestClient

from app import main
from app.camera_preview import preview


@pytest.fixture(autouse=True)
def reset_preview(monkeypatch):
    monkeypatch.setattr(preview, "latest", None)
    monkeypatch.setattr(preview, "sequence", 0)
    monkeypatch.setenv("AI_MODE", "mock")
    monkeypatch.setenv("VISION_MODE", "mock")


def test_no_frame_then_update_and_conditional_fetch(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    client = TestClient(main.app)
    assert client.get("/camera/latest").status_code == 204
    for number in (1, 2):
        data = b"\xff\xd8\xff" + bytes([number])
        result = client.post("/camera/frame", files={"file": ("frame.jpg", data, "image/jpeg")})
        assert result.json()["sequence"] == number
        frame = client.get("/camera/latest")
        assert frame.content == data
        assert frame.headers["content-type"] == "image/jpeg"
        assert frame.headers["cache-control"] == "no-store"
        assert frame.headers["x-frame-source"] == "/camera/frame"
        assert client.get("/camera/latest", headers={"If-None-Match": frame.headers["etag"]}).status_code == 304
    assert not list(tmp_path.iterdir())
    assert client.post("/camera/frame", files={"file": ("bad.jpg", b"bad", "image/jpeg")}).status_code == 415
    assert client.get("/camera/latest").content == data


@pytest.mark.parametrize("path,field", [("/upload", "file"), ("/analyze", "image"), ("/vision/analyze", "image")])
def test_existing_endpoints_publish(tmp_path, monkeypatch, path, field):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    client = TestClient(main.app)
    data = b"\x89PNG\r\n\x1a\nframe"
    response = client.post(path, files={field: ("frame.png", data, "image/png")}, data={"intent": "帮我打开微信"})
    assert response.status_code == 200
    latest = client.get("/camera/latest")
    assert latest.content == data
    assert latest.headers["x-frame-source"] == path


def test_preview_survives_model_failure(monkeypatch):
    monkeypatch.setenv("VISION_MODE", "openai_compatible")
    monkeypatch.setenv("VISION_API_KEY", "test")
    monkeypatch.setenv("VISION_MODEL", "test")

    async def failed(self, *args, **kwargs):
        return httpx.Response(401)

    monkeypatch.setattr(httpx.AsyncClient, "post", failed)
    client = TestClient(main.app)
    image = b"\xff\xd8\xffframe"
    assert client.post("/vision/analyze", files={"image": ("frame.jpg", image, "image/jpeg")}).status_code == 502
    assert client.get("/camera/latest").content == image
