import os
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app import main
from app.camera_preview import preview
from app.upload_cleanup import cleanup_expired


def test_only_expired_generated_images_removed(tmp_path):
    old = tmp_path / ("a" * 32 + ".jpg")
    old_png = tmp_path / ("b" * 32 + ".png")
    fresh = tmp_path / ("c" * 32 + ".jpg")
    unrelated = tmp_path / "my-photo.jpg"
    marker = tmp_path / ".gitkeep"
    nested = tmp_path / "nested"
    nested.mkdir()
    nested_file = nested / ("d" * 32 + ".jpg")
    for path in (old, old_png, fresh, unrelated, marker, nested_file):
        path.write_bytes(b"test")
        if path != fresh:
            os.utime(path, (time.time() - 60, time.time() - 60))
    assert cleanup_expired(tmp_path, 30) == 2
    assert not old.exists() and not old_png.exists()
    assert all(p.exists() for p in (fresh, unrelated, marker, nested_file))
    assert cleanup_expired(tmp_path / "missing", 30) == 0


def test_deletion_failure_retries_next_cycle(tmp_path, monkeypatch):
    path = tmp_path / ("a" * 32 + ".jpg")
    path.write_bytes(b"test")
    os.utime(path, (0, 0))
    original = Path.unlink

    def denied(*args, **kwargs):
        raise PermissionError("test")

    monkeypatch.setattr(Path, "unlink", denied)
    assert cleanup_expired(tmp_path, 30) == 0
    assert path.exists()
    monkeypatch.setattr(Path, "unlink", original)
    assert cleanup_expired(tmp_path, 30) == 1


def test_running_service_expires_upload_without_breaking_preview(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(preview, "latest", None)
    monkeypatch.setenv("UPLOAD_RETENTION_SECONDS", "0.1")
    monkeypatch.setenv("UPLOAD_CLEANUP_INTERVAL_SECONDS", "0.02")
    image = b"\xff\xd8\xfftest"
    with TestClient(main.app) as client:
        response = client.post("/upload", files={"file": ("test.jpg", image, "image/jpeg")})
        assert response.status_code == 200
        path = tmp_path / response.json()["filename"]
        assert path.read_bytes() == image
        deadline = time.monotonic() + 3
        while path.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert not path.exists()
        assert client.get("/camera/latest").content == image
        assert client.get("/health").status_code == 200
