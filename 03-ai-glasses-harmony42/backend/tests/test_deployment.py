"""Verify cloud startup and browser origin isolation without live model calls."""

import os
import subprocess
import sys
from unittest.mock import Mock

import pytest

from app import __main__ as startup


@pytest.mark.parametrize("configured,expected", [(None, 8000), ("10000", 10000)])
def test_startup_port(monkeypatch, configured, expected):
    monkeypatch.setattr(startup, "load_dotenv", lambda *args: None)
    if configured is None:
        monkeypatch.delenv("PORT", raising=False)
    else:
        monkeypatch.setenv("PORT", configured)
    run = Mock()
    monkeypatch.setattr(startup.uvicorn, "run", run)
    startup.main()
    run.assert_called_once_with("app.main:app", host="0.0.0.0", port=expected, workers=1,
                               ws="websockets", ws_max_queue=1,
                               ws_max_size=10 * 1024 * 1024 + 8,
                               ws_per_message_deflate=False)


@pytest.mark.parametrize("origins", ["", "https://frontend.example.com"])
def test_cors_isolation(origins):
    # A fresh process exercises the same import-time environment as Render.
    script = """
import os
from fastapi.testclient import TestClient
from app.main import app
client = TestClient(app)
allowed = 'https://frontend.example.com'
blocked = 'https://other.example.com'
configured = bool(os.environ['CORS_ALLOW_ORIGINS'])
for origin in (allowed, blocked):
    response = client.options('/analyze', headers={
        'Origin': origin, 'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type'})
    expected = allowed if configured and origin == allowed else None
    assert response.headers.get('access-control-allow-origin') == expected
    if expected:
        assert response.status_code == 200
response = client.get('/health', headers={'Origin': allowed})
assert response.json() == {'status': 'ok'}
assert response.headers.get('access-control-allow-origin') == (allowed if configured else None)
if configured:
    assert 'X-AI-Mode' in response.headers['access-control-expose-headers']
"""
    env = dict(os.environ, CORS_ALLOW_ORIGINS=origins, AI_MODE="mock", VISION_MODE="mock")
    result = subprocess.run([sys.executable, "-c", script], env=env,
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
