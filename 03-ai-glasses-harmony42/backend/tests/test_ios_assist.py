"""iOS 单步助手（B Agent）接口测试。

覆盖三件事：
1. 鉴权与参数校验（严格模式，多一个字段就拒）；
2. mock 模式的行为是否诚实（不冒充模型，没把握就返回 undetermined）；
3. 模型输出解析的硬约束（只能选候选集里的 id，多余的键一律拒绝）。
"""

import pytest
from fastapi.testclient import TestClient

from app.ios_assist_routes import _parse_target_id
from app.main import app

AUTH = {"Authorization": "Bearer test-assist-token"}


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("VISION_API_TOKEN", "test-assist-token")
    monkeypatch.setenv("VISION_MODE", "mock")
    return TestClient(app)


def _suggest_body(**overrides):
    body = {
        "goal": "帮我下一步",
        "image_width": 1170,
        "image_height": 2532,
        "recent_controls": [],
        "targets": [
            {"id": 0, "label": "返回", "bounds": [10, 100, 60, 40]},
            {"id": 1, "label": "下一步", "bounds": [900, 2300, 200, 80]},
        ],
        "session_id": "ios-assist-test",
    }
    body.update(overrides)
    return body


# --- 鉴权 ---

def test_suggest_requires_auth(client):
    assert client.post("/api/ios/v1/assist/suggest", json=_suggest_body()).status_code == 401


def test_latest_requires_auth(client):
    assert client.get("/api/ios/v1/assist/latest").status_code == 401


# --- 参数校验 ---

def test_duplicate_target_ids_rejected(client):
    body = _suggest_body(targets=[
        {"id": 0, "label": "A", "bounds": [0, 0, 10, 10]},
        {"id": 0, "label": "B", "bounds": [0, 0, 10, 10]},
    ])
    assert client.post("/api/ios/v1/assist/suggest", json=body, headers=AUTH).status_code == 422


def test_unknown_field_rejected(client):
    """严格模式：多一个字段就拒，防止客户端悄悄扩展契约。"""
    body = _suggest_body(coordinate=[1, 2])
    assert client.post("/api/ios/v1/assist/suggest", json=body, headers=AUTH).status_code == 422


def test_empty_targets_rejected(client):
    body = _suggest_body(targets=[])
    assert client.post("/api/ios/v1/assist/suggest", json=body, headers=AUTH).status_code == 422


def test_bad_session_id_rejected(client):
    body = _suggest_body(session_id="bad id!")
    assert client.post("/api/ios/v1/assist/suggest", json=body, headers=AUTH).status_code == 422


# --- mock 行为必须诚实 ---

def test_mock_picks_keyword_target_but_labels_itself_mock(client):
    resp = client.post("/api/ios/v1/assist/suggest", json=_suggest_body(), headers=AUTH)
    assert resp.status_code == 200
    data = resp.json()
    assert data["mode"] == "mock"
    assert data["status"] == "mock"
    assert data["target_id"] == 1
    assert "不是模型判断" in data["note"]


def test_mock_returns_undetermined_without_obvious_action(client):
    body = _suggest_body(targets=[
        {"id": 0, "label": "天气", "bounds": [0, 0, 10, 10]},
        {"id": 1, "label": "北京", "bounds": [0, 20, 10, 10]},
    ])
    resp = client.post("/api/ios/v1/assist/suggest", json=body, headers=AUTH)
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "undetermined"
    assert data["target_id"] is None
    assert "不猜测" in data["note"]


# --- reflect ---

def test_reflect_reports_change_and_no_change(client):
    same = {"goal": "帮我下一步", "action_description": "点击“下一步”",
            "before_fingerprint": "abc", "after_fingerprint": "abc",
            "session_id": "ios-assist-test"}
    resp = client.post("/api/ios/v1/assist/reflect", json=same, headers=AUTH)
    assert resp.status_code == 200
    assert resp.json()["outcome"] == "C"

    changed = dict(same, after_fingerprint="def")
    resp = client.post("/api/ios/v1/assist/reflect", json=changed, headers=AUTH)
    assert resp.status_code == 200
    assert resp.json()["outcome"] == "A"


# --- 取帧通道 ---

def test_latest_frame_404_before_any_upload(client):
    assert client.get("/api/ios/v1/assist/latest", headers=AUTH).status_code == 404


def test_latest_frame_returns_uploaded_jpeg(client):
    jpeg = b"\xff\xd8\xff\x00\x00\x00\x00\xff\xd9"
    client.post("/api/ios/v1/screen-session", json={"session_id": "assist-frame"},
                headers=AUTH)
    client.post("/api/ios/v1/screen-session/assist-frame/frame",
                content=jpeg, headers={**AUTH, "Content-Type": "image/jpeg"})
    resp = client.get("/api/ios/v1/assist/latest", headers=AUTH)
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "image/jpeg"
    assert resp.content == jpeg


# --- 模型输出解析的硬约束 ---

def test_parse_accepts_valid_id():
    assert _parse_target_id('{"target_id": 3}', {1, 3}) == 3


def test_parse_accepts_null():
    assert _parse_target_id('{"target_id": null}', {1}) is None


def test_parse_tolerates_markdown_fence():
    assert _parse_target_id('```json\n{"target_id": 1}\n```', {1}) == 1


def test_parse_rejects_extra_key():
    with pytest.raises(ValueError):
        _parse_target_id('{"target_id": 1, "coordinate": [1, 2]}', {1})


def test_parse_rejects_id_outside_candidates():
    with pytest.raises(ValueError):
        _parse_target_id('{"target_id": 9}', {1, 2})


def test_parse_rejects_string_id():
    """字符串 "1" 也要拒 —— 与 Android `takeUnless { it.isString }` 一致。"""
    with pytest.raises(ValueError):
        _parse_target_id('{"target_id": "1"}', {1})


def test_parse_rejects_non_json():
    with pytest.raises(ValueError):
        _parse_target_id("点击下一步", {1})
