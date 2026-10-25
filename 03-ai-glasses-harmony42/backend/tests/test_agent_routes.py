from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi.testclient import TestClient

from app import agent_routes, main


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv('VISION_API_TOKEN', 'test-token')
    monkeypatch.setenv('VISION_QWEN_API_KEY', 'test-model-key')
    monkeypatch.setenv('VISION_QWEN_MODEL', 'test-model')
    monkeypatch.setenv('VISION_QWEN_BASE_URL', 'https://model.example/v1')
    monkeypatch.setenv('VISION_QWEN_TIMEOUT', '120')
    return TestClient(main.app)


AUTH = {'Authorization': 'Bearer test-token'}
IMAGE = {'task_id': 'demo', 'images': ['data:image/jpeg;base64,abc']}


def test_agents_fail_closed_without_token(client, monkeypatch):
    monkeypatch.delenv('VISION_API_TOKEN')
    for path in ['/api/v1/object-recognition', '/v1/chat/completions']:
        response = client.post(path, json={})
        assert response.status_code == 503
        assert response.json()['error']['code'] == 'configuration_error'
    assert client.get('/health').status_code == 200


def test_auth_precedes_parsing_and_preserves_photo_route(client):
    for path in ['/api/v1/object-recognition', '/v1/chat/completions']:
        assert client.post(path, content='not json').status_code == 401
        assert client.post(path, json={}, headers={'Authorization': 'Bearer wrong'}).status_code == 401
    assert client.post('/camera/frame', files={'file': ('a.jpg', b'\xff\xd8\xffimage', 'image/jpeg')}).status_code == 200


def test_object_request_and_response(client, monkeypatch):
    chat = AsyncMock(return_value={'choices': [{'message': {'content': '杯子'}}]})
    monkeypatch.setattr(agent_routes, 'chat', chat)
    response = client.post('/api/v1/object-recognition', headers=AUTH, json=IMAGE)
    assert response.status_code == 200
    assert response.json()['answer'] == '杯子'
    assert response.json()['task_id'] == 'demo'
    assert chat.call_args.args[0]['messages'][1]['content'][0]['image_url']['url'] == IMAGE['images'][0]
    assert response.headers['cache-control'] == 'no-store'


def test_proxy_and_validation(client, monkeypatch):
    result = {'choices': [{'message': {'content': 'screen'}}]}
    chat = AsyncMock(return_value=result)
    monkeypatch.setattr(agent_routes, 'chat', chat)
    payload = {'messages': [{'role': 'user', 'content': 'screen'}], 'stream': False}
    assert client.post('/v1/chat/completions', headers=AUTH, json=payload).json() == result
    assert chat.call_args.args[0] == payload
    for invalid in [{'messages': [] , 'stream': True}, {'messages': 'bad'}]:
        assert client.post('/v1/chat/completions', headers=AUTH, json=invalid).status_code == 422
    assert client.post('/v1/chat/completions', headers=AUTH, content='{').status_code == 422
    assert client.post('/api/v1/object-recognition', headers=AUTH, json={'images': ['local.jpg']}).status_code == 422


@pytest.mark.parametrize('status,body,expected', [
    (200, {'choices': [{'message': {'content': '杯子'}}]}, 200),
    (401, {'error': 'secret upstream details'}, 503),
    (200, {'choices': []}, 502),
    (200, {'wrong': 'shape'}, 502),
])
def test_real_adapter_with_mock_transport(client, monkeypatch, status, body, expected):
    real_client = httpx.AsyncClient

    def upstream(request):
        import json
        assert str(request.url) == 'https://model.example/v1/chat/completions'
        assert request.headers['authorization'] == 'Bearer test-model-key'
        assert json.loads(request.content)['model'] == 'test-model'
        return httpx.Response(status, json=body)

    monkeypatch.setattr(agent_routes.httpx, 'AsyncClient',
                        lambda **kwargs: real_client(transport=httpx.MockTransport(upstream), **kwargs))
    response = client.post('/api/v1/object-recognition', headers=AUTH, json=IMAGE)
    assert response.status_code == expected
    assert 'secret upstream details' not in response.text


def test_timeout_and_missing_cloud_key(client, monkeypatch):
    real_client = httpx.AsyncClient

    def upstream(request):
        raise httpx.ReadTimeout('timeout')

    monkeypatch.setattr(agent_routes.httpx, 'AsyncClient',
                        lambda **kwargs: real_client(transport=httpx.MockTransport(upstream), **kwargs))
    assert client.post('/api/v1/object-recognition', headers=AUTH, json=IMAGE).status_code == 504
    monkeypatch.delenv('VISION_QWEN_API_KEY')
    response = client.post('/api/v1/object-recognition', headers=AUTH, json=IMAGE)
    assert response.status_code == 503
    assert response.json()['error']['code'] == 'configuration_error'


def test_request_limit_and_docs(client, monkeypatch):
    monkeypatch.setattr(agent_routes, 'chat', AsyncMock())
    assert client.post('/v1/chat/completions', headers=AUTH,
                       content=b'x' * (48 * 1024 * 1024 + 1)).status_code == 413
    schema = client.get('/openapi.json').json()
    assert schema['paths']['/api/v1/object-recognition']['post']['security']
    proxy = schema['paths']['/v1/chat/completions']['post']
    assert proxy['security']
    assert 'requestBody' in proxy
