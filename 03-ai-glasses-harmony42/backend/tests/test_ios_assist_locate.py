"""视觉定位（/locate）与编号标注图的测试。

重点验证三件事：
1. 定位结果会被严格清洗（坏坐标、超长文案、布尔值都丢弃），但**不整单拒绝**；
2. mock 模式诚实承认"不做视觉定位"，不伪造图标候选；
3. 云端预测时发给模型的是**带编号红框的图**，而不是原图；标注失败必须退回原图。
"""

import base64
import io
import json

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.ios_assist_routes import _annotate, _parse_locate
from app.main import app

AUTH = {"Authorization": "Bearer test-assist-token"}


def _jpeg_bytes(width=200, height=400, color=(255, 255, 255)) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buffer, format="JPEG")
    return buffer.getvalue()


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("VISION_API_TOKEN", "test-assist-token")
    monkeypatch.setenv("VISION_MODE", "mock")
    return TestClient(app)


@pytest.fixture
def cloud_client(monkeypatch):
    monkeypatch.setenv("VISION_API_TOKEN", "test-assist-token")
    monkeypatch.setenv("VISION_MODE", "ark")
    monkeypatch.setenv("VISION_API_KEY", "fake-key")
    monkeypatch.setenv("VISION_MODEL", "fake-model")
    return TestClient(app)


def _locate_body(**overrides):
    body = {
        "image_width": 200,
        "image_height": 400,
        "session_id": "ios-locate-test",
        "image_base64": _b64(_jpeg_bytes()),
    }
    body.update(overrides)
    return body


def _stub_chat(monkeypatch, payload: str, captured: dict):
    async def fake_chat(base, key, model, timeout, system, user, image):
        captured["system"] = system
        captured["user"] = user
        captured["image"] = image
        return payload

    monkeypatch.setattr("app.ios_assist_routes._chat", fake_chat)


# --- 鉴权与契约 ---

def test_locate_requires_auth(client):
    assert client.post("/api/ios/v1/assist/locate", json=_locate_body()).status_code == 401


def test_locate_rejects_unknown_field(client):
    body = _locate_body(targets=[])
    assert client.post("/api/ios/v1/assist/locate", json=body, headers=AUTH).status_code == 422


def test_locate_requires_image(client):
    body = _locate_body()
    del body["image_base64"]
    assert client.post("/api/ios/v1/assist/locate", json=body, headers=AUTH).status_code == 422


# --- mock 必须诚实 ---

def test_locate_mock_is_honest(client):
    response = client.post("/api/ios/v1/assist/locate", json=_locate_body(), headers=AUTH)
    assert response.status_code == 200
    data = response.json()
    assert data["mode"] == "mock"
    assert data["status"] == "mock"
    assert data["elements"] == []
    assert "不做视觉定位" in data["note"]


# --- 云端定位 ---

def test_locate_cloud_converts_normalized_box_to_pixels(cloud_client, monkeypatch):
    payload = json.dumps({"elements": [
        {"label": "返回", "kind": "icon", "box": [0, 0, 100, 50]},
        {"label": "下一步", "kind": "text", "box": [500, 900, 400, 60]},
    ]})
    _stub_chat(monkeypatch, payload, {})
    response = client_post_locate(cloud_client)
    assert response.status_code == 200
    data = response.json()
    assert data["mode"] == "cloud"
    assert data["dropped"] == 0
    # 200x400 图：100/1000*200=20 宽，50/1000*400=20 高
    assert data["elements"][0] == {"label": "返回", "kind": "icon", "bounds": [0, 0, 20, 20]}
    assert data["elements"][1]["bounds"] == [100, 360, 80, 24]


def client_post_locate(client):
    return client.post("/api/ios/v1/assist/locate", json=_locate_body(), headers=AUTH)


def test_locate_drops_bad_entries_but_keeps_good_ones(cloud_client, monkeypatch):
    payload = json.dumps({"elements": [
        {"label": "关闭", "kind": "icon", "box": [900, 10, 60, 60]},      # 合法
        {"label": "零宽", "kind": "icon", "box": [0, 0, 0, 50]},          # w=0
        {"label": "越界", "kind": "icon", "box": [0, 0, 2000, 50]},       # 超过 1000
        {"label": "负坐标", "kind": "icon", "box": [-5, 0, 50, 50]},      # 负数
        {"label": "布尔坐标", "kind": "icon", "box": [True, 0, 50, 50]},  # bool 不是坐标
        {"label": "x" * 60, "kind": "text", "box": [0, 0, 50, 50]},       # 文案过长
        {"label": "缺box", "kind": "icon"},                               # 缺字段
        "不是对象",                                                       # 类型错
        {"label": "正常", "kind": "text", "box": [100, 100, 100, 100]},   # 合法
    ]})
    _stub_chat(monkeypatch, payload, {})
    data = client_post_locate(cloud_client).json()
    labels = [e["label"] for e in data["elements"]]
    assert labels == ["关闭", "正常"]
    assert data["dropped"] == 7


def test_locate_rejects_non_json_model_output(cloud_client, monkeypatch):
    _stub_chat(monkeypatch, "我觉得应该点右上角", {})
    assert client_post_locate(cloud_client).status_code == 422


def test_parse_locate_rejects_missing_elements_array():
    with pytest.raises(ValueError):
        _parse_locate('{"items":[]}', 200, 400)


def test_parse_locate_accepts_fenced_json():
    elements, dropped = _parse_locate(
        '```json\n{"elements":[{"label":"菜单","kind":"icon","box":[0,0,50,50]}]}\n```',
        200, 400,
    )
    assert dropped == 0
    assert elements[0].label == "菜单"


# --- 编号标注图 ---

def test_annotate_returns_valid_jpeg():
    from app.ios_assist_routes import TargetIn

    image = _jpeg_bytes()
    marked = _annotate(image, [TargetIn(id=3, label="下一步", bounds=[10, 20, 80, 40])])
    assert marked is not None
    assert marked != image
    with Image.open(io.BytesIO(marked)) as im:
        assert im.size == (200, 400)


def test_annotate_returns_none_on_broken_image():
    from app.ios_assist_routes import TargetIn

    assert _annotate(b"not-an-image", [TargetIn(id=0, label="A", bounds=[0, 0, 10, 10])]) is None


def _suggest_body(**overrides):
    body = {
        "goal": "帮我下一步",
        "image_width": 200,
        "image_height": 400,
        "recent_controls": [],
        "targets": [{"id": 1, "label": "下一步", "bounds": [10, 300, 120, 50]}],
        "session_id": "ios-assist-test",
        "image_base64": _b64(_jpeg_bytes()),
    }
    body.update(overrides)
    return body


def test_suggest_cloud_sends_annotated_image(cloud_client, monkeypatch):
    captured: dict = {}
    _stub_chat(monkeypatch, '{"target_id":1}', captured)
    response = cloud_client.post("/api/ios/v1/assist/suggest", json=_suggest_body(), headers=AUTH)
    assert response.status_code == 200
    data = response.json()
    assert data["annotated"] is True
    assert data["target_id"] == 1
    # 发给模型的图必须是标注后的图，而不是客户端原图
    assert captured["image"] != _jpeg_bytes()
    assert "红框" in captured["user"]


def test_suggest_falls_back_to_raw_image_when_annotation_fails(cloud_client, monkeypatch):
    captured: dict = {}
    _stub_chat(monkeypatch, '{"target_id":1}', captured)
    monkeypatch.setattr("app.ios_assist_routes._annotate", lambda image, targets: None)
    response = cloud_client.post("/api/ios/v1/assist/suggest", json=_suggest_body(), headers=AUTH)
    data = response.json()
    assert data["annotated"] is False
    assert captured["image"] == _jpeg_bytes()
    assert "红框" not in captured["user"]


def test_suggest_mock_does_not_claim_annotation(client):
    response = client.post("/api/ios/v1/assist/suggest", json=_suggest_body(), headers=AUTH)
    data = response.json()
    assert data["mode"] == "mock"
    assert data["annotated"] is False
