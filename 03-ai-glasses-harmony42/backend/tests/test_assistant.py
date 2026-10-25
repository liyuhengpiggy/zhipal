import base64
import time
from dataclasses import replace
from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi.testclient import TestClient

from app import assistant_routes as routes, main
from app.camera_preview import Frame

AUTH = {'Authorization': 'Bearer backend-test'}
PREFIX = '/api/v1/assistant'


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv('VISION_API_TOKEN', 'backend-test')
    monkeypatch.delenv('ASSISTANT_OCR_TOKEN', raising=False)
    monkeypatch.delenv('ASSISTANT_OCR_BASE_URL', raising=False)
    monkeypatch.setattr(routes.preview, 'latest', Frame(b'\xff\xd8\xffsample', 'image/jpeg',
                        '/ws/camera/upload', '2026-10-25T00:00:00Z', 10))
    return TestClient(main.app)


def submit(client, mode='objects', **kwargs):
    return client.post(PREFIX + '/analyze', headers=AUTH, json={'mode': mode, **kwargs})


def test_auth_before_parsing_and_fail_closed(client, monkeypatch):
    assert client.post(PREFIX + '/analyze', content='bad').status_code == 401
    assert client.get(PREFIX + '/capabilities').status_code == 401
    monkeypatch.delenv('VISION_API_TOKEN')
    assert submit(client).status_code == 503
    assert client.get('/health').status_code == 200


@pytest.mark.parametrize('mode', ['objects', 'scene'])
def test_snapshot_is_immutable_and_modes_are_distinct(client, monkeypatch, mode):
    original = routes.preview.latest
    model = AsyncMock(return_value={'status': 'succeeded', 'answer': '杯子', 'review_required': True})
    monkeypatch.setattr(routes, 'object_recognition', model)
    response = submit(client, mode)
    assert response.status_code == 200
    body = response.json()
    assert body['frame']['sequence'] == 10
    assert body['frame']['original_sha256'] == body['frame']['submitted_sha256']
    request = model.call_args.args[0]
    assert base64.b64decode(request.images[0].split(',')[1]) == original.data
    assert ('主要物品' if mode == 'objects' else '场景') in request.question
    assert routes.preview.latest is original
    assert response.headers['cache-control'] == 'no-store'


@pytest.mark.parametrize('state,code', [('missing', 'no_camera_frame'), ('stale', 'stale_camera_frame')])
def test_missing_or_stale_frame(client, monkeypatch, state, code):
    frame = None if state == 'missing' else replace(routes.preview.latest, received_monotonic=time.monotonic()-10)
    monkeypatch.setattr(routes.preview, 'latest', frame)
    response = submit(client)
    assert response.status_code == 409
    assert response.json()['error']['code'] == code


@pytest.mark.parametrize('payload', [{'mode': 'medicine'}, {'mode': 'objects', 'mirror': True},
                                     {'mode': 'objects', 'url': 'http://arbitrary'}])
def test_strict_input(client, payload):
    assert client.post(PREFIX+'/analyze', headers=AUTH, json=payload).status_code == 422


def test_no_backend_mirror_option(client):
    assert 'assistant-mirror' not in client.get('/').text
    assert not hasattr(routes, 'flip_jpeg')


def test_ocr_requires_separate_token(client):
    assert submit(client, 'read_text').status_code == 422


def test_ocr_roundtrip_preserves_fields(client, monkeypatch):
    calls = []
    real_client = httpx.AsyncClient
    fields = {'medicine_name': None, 'custom_upstream_field': '原样保留'}

    def upstream(request):
        calls.append(request)
        assert request.headers['authorization'] == 'Bearer ocr-only-test'
        if request.method == 'PUT':
            assert request.content == routes.preview.latest.data
        return httpx.Response(200, json={'status': 'succeeded' if request.method == 'GET' else 'collecting',
                                       'fields': fields})

    monkeypatch.setattr(routes.httpx, 'AsyncClient', lambda **kw: real_client(transport=httpx.MockTransport(upstream), **kw))
    headers = {**AUTH, 'X-OCR-Token': 'ocr-only-test'}
    original = routes.preview.latest
    response = client.post(PREFIX+'/analyze', headers=headers, json={'mode': 'read_text'})
    assert response.status_code == 200
    body = response.json()
    task_id = body['task_id']
    assert len(task_id) == 42 and task_id.startswith('assistant-')
    assert body['ocr']['fields'] == fields
    polled = client.get(PREFIX+'/ocr/'+task_id, headers=headers)
    assert polled.json()['fields'] == fields
    assert [r.method for r in calls] == ['POST', 'PUT', 'GET']
    assert all(r.url.host == 'medicine-vision-ocr.onrender.com' for r in calls)
    assert routes.preview.latest is original
    assert 'ocr-only-test' not in response.text


@pytest.mark.parametrize('status,body,expected', [(401, {}, 401), (302, {}, 502),
                                              (200, [], 502), (200, {}, 502)])
def test_ocr_safe_errors(client, monkeypatch, status, body, expected):
    monkeypatch.setenv('ASSISTANT_OCR_TOKEN', 'ocr-test')
    real_client = httpx.AsyncClient
    monkeypatch.setattr(routes.httpx, 'AsyncClient', lambda **kw: real_client(
        transport=httpx.MockTransport(lambda r: httpx.Response(status, json=body)), **kw))
    response = submit(client, 'read_text')
    assert response.status_code == expected
    assert 'ocr-test' not in response.text


@pytest.mark.parametrize('base', ['http://example.com', 'https://user:pass@example.com',
                                 'https://example.com/?token=secret', 'https://[broken'])
def test_invalid_configuration(client, monkeypatch, base):
    monkeypatch.setenv('ASSISTANT_OCR_BASE_URL', base)
    response = client.get(PREFIX+'/capabilities', headers=AUTH)
    assert response.status_code == 503
    assert base not in response.text


def test_capabilities_and_old_homepage(client, monkeypatch):
    monkeypatch.setenv('VISION_QWEN_API_KEY', 'private-cloud-key')
    response = client.get(PREFIX+'/capabilities', headers=AUTH)
    assert response.json()['vision_configured'] is True
    assert response.json()['automatic_analysis'] is False
    assert 'private-cloud-key' not in response.text
    home = client.get('/').text
    for text in ['assistant-form', 'camera', '视觉分析', '上传']:
        assert text in home


def test_busy_and_invalid_task(client, monkeypatch):
    class Busy:
        def locked(self):
            return True
    monkeypatch.setattr(routes, 'analysis_lock', Busy())
    assert submit(client).status_code == 429
    assert client.get(PREFIX+'/ocr/not-an-assistant-task', headers=AUTH).status_code == 422
