import struct
import time

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app import main
from app.camera_preview import CameraPreview
from app.enhancement_runtime import EnhancementRuntime


def jpeg(level):
    image = np.full((1080, 1920, 3), level, dtype=np.uint8)
    return cv2.imencode('.jpg', image, [cv2.IMWRITE_JPEG_QUALITY, 10])[1].tobytes()


def packet(seq, data):
    return struct.pack('<II', 0x4B564630, seq) + data


@pytest.fixture
def camera(monkeypatch, tmp_path):
    import app.enhancement_runtime as worker
    preview = CameraPreview()
    runtime = EnhancementRuntime()
    monkeypatch.setattr(main, 'preview', preview)
    monkeypatch.setattr(main, 'runtime', runtime)
    monkeypatch.setattr(worker, 'preview', preview)
    monkeypatch.setattr(main, 'UPLOAD_DIR', tmp_path)
    with TestClient(main.app) as client:
        yield client, preview, runtime, tmp_path


def wait_for(client, predicate):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        stats = client.get('/camera/performance').json()
        if predicate(stats):
            return stats
        time.sleep(.01)
    raise AssertionError(stats)


def test_sequence_latest_bytes_metrics_and_reconnect(camera):
    client, preview, runtime, directory = camera
    frames = {seq: jpeg(seq * 60) for seq in (1, 2, 3)}
    assert client.get('/camera/performance').json()['ws_connected'] is False
    with client.websocket_connect('/ws/camera/upload') as ws:
        assert client.get('/camera/performance').json()['ws_connected'] is True
        for seq in (1, 2, 3, 2):
            ws.send_bytes(packet(seq, frames[seq]))
        stats = wait_for(client, lambda s: s['ws_received_total'] == 4)
        assert stats['latest_received_sequence'] == 3
        assert stats['ws_dropped_out_of_order'] == 1
        assert stats['ws_frame_fps'] == .8
        response = client.get('/camera/latest')
        assert response.content == frames[3]
        assert response.headers['content-type'] == 'image/jpeg'
        assert response.headers['x-frame-source'] == '/ws/camera/upload'
        assert cv2.imdecode(np.frombuffer(response.content, np.uint8), 1).shape == (1080, 1920, 3)
        assert client.get('/camera/latest', headers={'If-None-Match': response.headers['etag']}).status_code == 304
        wait_for(client, lambda s: s['processed_total'] >= 1 and not s['pending_latest_only'])
        assert runtime.pair[0].client_sequence == 3
        assert runtime.config.enable_clahe and runtime.config.enable_brightness_stabilization
        assert not list(directory.iterdir())
    wait_for(client, lambda s: not s['ws_connected'])
    with client.websocket_connect('/ws/camera/upload') as ws:
        ws.send_bytes(packet(2, frames[2]))
        wait_for(client, lambda s: s['latest_received_sequence'] == 2)
        assert client.get('/camera/latest').content == frames[2]
        ws.send_bytes(packet(2, frames[1]))
        wait_for(client, lambda s: s['ws_dropped_out_of_order'] == 2)
        assert client.get('/camera/latest').content == frames[2]
        ws.send_bytes(packet(4, frames[1]))
        wait_for(client, lambda s: s['latest_received_sequence'] == 4)


def test_http_upload_does_not_change_ws_sequence_watermark(camera):
    client, preview, runtime, directory = camera
    data = jpeg(100)
    # Legacy uploads may already have a much higher internal frame version.
    for _ in range(5):
        assert client.post('/camera/frame', files={'file': ('f.jpg', data, 'image/jpeg')}).status_code == 200
    with client.websocket_connect('/ws/camera/upload') as ws:
        ws.send_bytes(packet(1, data))
        wait_for(client, lambda s: s['latest_received_sequence'] == 1)
        assert client.post('/upload', files={'file': ('f.jpg', data, 'image/jpeg')}).status_code == 200
        ws.send_bytes(packet(2, data))
        stats = wait_for(client, lambda s: s['latest_received_sequence'] == 2)
        assert stats['latest_internal_sequence'] == 8


@pytest.mark.parametrize('message,code', [
    ('text', 1003),
    (b'bad', 1008),
    (struct.pack('<II', 123, 100) + b'\xff\xd8\xff\xff\xd9', 1008),
    (packet(100, b'not a jpeg'), 1008),
    (packet(100, b'\xff\xd8\xfftruncated'), 1008),
    (packet(100, b'x' * (main.MAX_IMAGE_BYTES + 1)), 1009),
], ids=['text', 'short', 'magic', 'non-jpeg', 'truncated', 'oversize'])
def test_invalid_messages_do_not_replace_latest(camera, message, code):
    client, preview, runtime, directory = camera
    with client.websocket_connect('/ws/camera/upload') as ws:
        if isinstance(message, str):
            ws.send_text(message)
        else:
            ws.send_bytes(message)
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_bytes()
        assert exc.value.code == code
    assert preview.latest is None and preview.ws_sequence is None
    assert preview.ws_connections == 0


def test_new_connection_takes_over_and_old_connection_cannot_overwrite(camera):
    client, preview, runtime, directory = camera
    data, rebooted = jpeg(100), jpeg(200)
    with client.websocket_connect('/ws/camera/upload') as first:
        first.send_bytes(packet(59191, data))
        wait_for(client, lambda s: s['latest_received_sequence'] == 59191)
        with client.websocket_connect('/ws/camera/upload') as second:
            assert client.get('/camera/performance').json()['ws_connections'] == 2
            # Opening an idle socket must not interrupt the active uploader.
            first.send_bytes(packet(59192, data))
            wait_for(client, lambda s: s['latest_received_sequence'] == 59192)
            second.send_bytes(packet(0, rebooted))
            wait_for(client, lambda s: s['ws_active_session'] == 2)
            assert client.get('/camera/latest').content == rebooted
            first.send_bytes(packet(99999, data))
            wait_for(client, lambda s: s['ws_dropped_superseded'] == 1)
            assert client.get('/camera/latest').content == rebooted
            second.send_bytes(packet(0, data))
            wait_for(client, lambda s: s['ws_dropped_out_of_order'] == 1)
            second.send_bytes(packet(1, rebooted))
            wait_for(client, lambda s: s['latest_received_sequence'] == 1)
        assert client.get('/camera/performance').json()['ws_connected'] is True
        first.send_bytes(packet(100000, data))
        wait_for(client, lambda s: s['ws_dropped_superseded'] == 2)
        assert client.get('/camera/latest').content == rebooted
        with client.websocket_connect('/ws/camera/upload') as third:
            third.send_bytes(packet(1, data))
            wait_for(client, lambda s: s['ws_active_session'] == 3)
            assert client.get('/camera/latest').content == data


def test_invalid_new_connection_does_not_reset_active_stream(camera):
    client, preview, runtime, directory = camera
    data = jpeg(100)
    with client.websocket_connect('/ws/camera/upload') as first:
        first.send_bytes(packet(100, data))
        wait_for(client, lambda s: s['latest_received_sequence'] == 100)
        with client.websocket_connect('/ws/camera/upload') as second:
            second.send_bytes(b'bad')
            with pytest.raises(WebSocketDisconnect):
                second.receive_bytes()
        first.send_bytes(packet(101, data))
        stats = wait_for(client, lambda s: s['latest_received_sequence'] == 101)
        assert stats['ws_active_session'] == 1
        assert stats['ws_dropped_superseded'] == 0
