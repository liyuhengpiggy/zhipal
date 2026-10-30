"""iOS 屏幕共享会话接口测试（本地，使用 TestClient 与 mock 视觉模式）。"""

import os

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("VISION_API_TOKEN", "test-ios-token")
    monkeypatch.setenv("VISION_MODE", "mock")
    return TestClient(app)


def _auth_headers():
    return {"Authorization": "Bearer test-ios-token"}


def test_create_session_requires_auth(client):
    resp = client.post("/api/ios/v1/screen-session", json={"session_id": "sess1"})
    assert resp.status_code == 401


def test_create_session(client):
    resp = client.post("/api/ios/v1/screen-session",
                       json={"session_id": "sess1"}, headers=_auth_headers())
    assert resp.status_code == 200
    data = resp.json()
    assert data["session_id"] == "sess1"
    assert data["status"] == "created"


def test_create_session_invalid_id(client):
    resp = client.post("/api/ios/v1/screen-session",
                       json={"session_id": "bad id!"}, headers=_auth_headers())
    assert resp.status_code == 422


def test_upload_frame(client):
    client.post("/api/ios/v1/screen-session", json={"session_id": "sess2"}, headers=_auth_headers())
    jpeg = b"\xff\xd8\xff\x00\x00\x00\x00\xff\xd9"
    resp = client.post("/api/ios/v1/screen-session/sess2/frame",
                       content=jpeg, headers={**_auth_headers(), "Content-Type": "image/jpeg"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is True
    assert data["frame_index"] == 1


def test_upload_frame_rejects_wrong_type(client):
    client.post("/api/ios/v1/screen-session", json={"session_id": "sess3"}, headers=_auth_headers())
    resp = client.post("/api/ios/v1/screen-session/sess3/frame",
                       content=b"not jpeg", headers={**_auth_headers(), "Content-Type": "text/plain"})
    assert resp.status_code == 415


def test_analyze_returns_mock(client):
    client.post("/api/ios/v1/screen-session", json={"session_id": "sess4"}, headers=_auth_headers())
    jpeg = b"\xff\xd8\xff\x00\x00\x00\x00\xff\xd9"
    client.post("/api/ios/v1/screen-session/sess4/frame",
                content=jpeg, headers={**_auth_headers(), "Content-Type": "image/jpeg"})
    resp = client.post("/api/ios/v1/screen-session/sess4/analyze", headers=_auth_headers())
    assert resp.status_code == 200
    data = resp.json()
    # 模拟模式：不虚构画面
    assert data["status"] == "mock"
    assert data["mode"] == "mock"


def test_analyze_no_frame(client):
    client.post("/api/ios/v1/screen-session", json={"session_id": "sess5"}, headers=_auth_headers())
    resp = client.post("/api/ios/v1/screen-session/sess5/analyze", headers=_auth_headers())
    assert resp.status_code == 409


def test_end_session(client):
    client.post("/api/ios/v1/screen-session", json={"session_id": "sess6"}, headers=_auth_headers())
    resp = client.delete("/api/ios/v1/screen-session/sess6", headers=_auth_headers())
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ended"
