import asyncio

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app import main
from app.enhancement_runtime import EnhancementRuntime
from app.image_enhancement import EnhancementConfig, ImageEnhancer


def jpeg(level=70):
    image = np.full((120,160,3),level,np.uint8)
    cv2.putText(image,'Text 123',(5,65),cv2.FONT_HERSHEY_SIMPLEX,0.6,(180,180,180),1)
    return cv2.imencode('.jpg',image)[1].tobytes()


def test_modes_original_preserved_and_valid_jpeg():
    data = jpeg()
    for mode in 'ABCD':
        enhancer=ImageEnhancer(EnhancementConfig(enable_brightness_stabilization=mode!='A',
            enable_clahe=mode in 'CD',enable_sharpen=mode=='D'))
        result=enhancer.process(data)
        assert (result.width,result.height)==(160,120)
        assert cv2.imdecode(np.frombuffer(result.data,np.uint8),1).shape==(120,160,3)
        assert result.decode_ms>=0 and result.enhance_ms>=0
        if mode=='A': assert result.data==data


def test_ema_reduces_small_brightness_flicker_and_resets_on_cut():
    enhancer=ImageEnhancer(EnhancementConfig(enable_clahe=False,enable_sharpen=False))
    levels=[90,100]*20
    outputs=[]
    for level in levels:
        output=enhancer.apply(np.full((64,64,3),level,np.uint8))
        outputs.append(output.mean())
    assert np.std(outputs[10:]) < np.std(levels[10:])*0.8
    output=enhancer.apply(np.full((64,64,3),220,np.uint8))
    assert abs(output.mean()-220)<2


@pytest.mark.parametrize('levels', [(90, 100), (80, 110), (200, 220)])
def test_ema_smooths_exposure_without_dark_bias(levels):
    enhancer = ImageEnhancer(EnhancementConfig(enable_clahe=False))
    outputs = [enhancer.apply(np.full((64, 64, 3), level, np.uint8)).mean()
               for level in levels*25]
    assert np.std(outputs[20:]) < np.std(levels)*.5
    assert abs(np.mean(outputs[20:])-np.mean(levels)) < 2


def test_highlights_do_not_disable_mid_tone_stabilization():
    enhancer = ImageEnhancer(EnhancementConfig(enable_clahe=False))
    outputs = []
    for level in (90, 100)*25:
        image = np.full((80, 80, 3), level, np.uint8)
        image[:2, :, :] = 255
        output = enhancer.apply(image)
        outputs.append(output[2:].mean())
        assert np.all(output[2:] < 255)
    assert np.std(outputs[20:]) < 2
    assert abs(np.mean(outputs[20:])-95) < 2


def test_structure_cut_resets_with_similar_brightness():
    enhancer = ImageEnhancer(EnhancementConfig(enable_clahe=False))
    first = np.full((96, 128, 3), 80, np.uint8)
    first[:, 64:] = 140
    second = np.full_like(first, 90)
    second[48:] = 150
    enhancer.apply(first)
    output = enhancer.apply(second)
    assert np.max(np.abs(output.astype(np.int16)-second.astype(np.int16))) <= 1


def test_gradual_exposure_does_not_periodically_reset_ema():
    enhancer = ImageEnhancer(EnhancementConfig(enable_clahe=False))
    expected = 40.0
    for level in range(40, 201, 5):
        expected = .15*level+.85*expected
        enhancer.apply(np.full((64, 64, 3), level, np.uint8))
        assert enhancer.ema == pytest.approx(expected)


def test_brightening_curve_is_monotone_and_preserves_black_white():
    enhancer = ImageEnhancer(EnhancementConfig(enable_clahe=False))
    ramp = np.broadcast_to(np.arange(256, dtype=np.uint8), (64, 256))
    bgr = cv2.cvtColor(ramp, cv2.COLOR_GRAY2BGR)
    enhancer.apply(bgr)
    # Model a preceding brighter reference without a scene cut.
    enhancer.ema = ramp.mean()*1.25
    output = enhancer.apply(bgr)[0, :, 0]
    assert output[0] == 0 and output[-1] == 255
    assert np.all(np.diff(output.astype(np.int16)) >= 0)
    assert np.all(output >= ramp[0])
    assert np.all(output[:-1] < 255)


def test_contrast_boost_on_compressed_low_contrast_text():
    rng = np.random.default_rng(17)
    mask = np.zeros((180, 320), np.uint8)
    cv2.putText(mask, 'Text 123 Il1', (15, 90), cv2.FONT_HERSHEY_SIMPLEX, .8, 255, 2)
    image = np.clip(150-32*(mask/255)+rng.normal(0, 1.5, mask.shape), 0, 255).astype(np.uint8)
    compressed = cv2.imencode('.jpg', image, [cv2.IMWRITE_JPEG_QUALITY, 45])[1]
    bgr = cv2.imdecode(compressed, cv2.IMREAD_COLOR)
    enhancer = ImageEnhancer(EnhancementConfig(enable_brightness_stabilization=False))
    enhanced = enhancer.apply(bgr)[:, :, 0]
    raw = bgr[:, :, 0]
    background = cv2.dilate(mask, np.ones((15, 15), np.uint8)) == 0
    strokes = cv2.erode(mask, np.ones((2, 2), np.uint8)) > 0
    contrast = lambda frame: float(np.mean(frame[background])-np.mean(frame[strokes]))
    assert contrast(enhanced) > contrast(raw)*1.1
    # This synthetic region is flat; avoid unconstrained noise amplification.
    assert np.std(enhanced[background]) < max(1, np.std(raw[background]))*2.5


def test_jpeg_fps_excludes_png():
    from app.camera_preview import CameraPreview
    preview = CameraPreview()
    preview.publish(b'jpeg', 'image/jpeg', 'test')
    preview.publish(b'png', 'image/png', 'test')
    assert preview.receive_fps() == .4
    assert preview.jpeg_receive_fps() == .2


def test_invalid_decode_and_fallback_endpoint():
    with pytest.raises(ValueError): ImageEnhancer(EnhancementConfig()).process(b'bad')
    response=TestClient(main.app).post('/image/enhance',files={'image':('bad.jpg',b'\xff\xd8\xffbad','image/jpeg')})
    assert response.status_code==200
    assert response.headers['x-enhancement']=='fallback-original'
    assert response.content==b'\xff\xd8\xffbad'


def test_bounds_reject_invalid_config():
    response=TestClient(main.app).put('/camera/enhancement',json={'brightness_alpha':0})
    assert response.status_code==422


@pytest.mark.parametrize('strength', [0, 0.4, 1])
def test_byte_sharpen_matches_bounded_float_reference(strength):
    # A noisy colored image exercises positive/negative detail, weak differences,
    # saturated pixels and chroma preservation, not just a flat test image.
    bgr = np.random.default_rng(7).integers(0, 256, (96, 128, 3), dtype=np.uint8)
    ycc = cv2.cvtColor(bgr, cv2.COLOR_BGR2YCrCb)
    y = cv2.extractChannel(ycc, 0)
    detail = y.astype(np.int16)-cv2.GaussianBlur(y, (0, 0), 1).astype(np.int16)
    detail[np.abs(detail) < 3] = 0
    expected = np.clip(np.rint(y+strength*np.clip(detail, -16, 16)), 0, 255).astype(np.uint8)
    cv2.insertChannel(expected, ycc, 0)
    reference = cv2.cvtColor(ycc, cv2.COLOR_YCrCb2BGR)
    enhancer = ImageEnhancer(EnhancementConfig(enable_brightness_stabilization=False,
        enable_clahe=False, enable_sharpen=True, sharpen_strength=strength))
    actual = enhancer.apply(bgr)
    assert np.max(np.abs(actual.astype(np.int16)-reference.astype(np.int16))) <= 1


def test_config_change_discards_in_flight_sample(monkeypatch):
    import threading
    import time
    import app.enhancement_runtime as worker
    from app.camera_preview import CameraPreview
    preview = CameraPreview()
    monkeypatch.setattr(worker, 'preview', preview)
    entered, release = threading.Event(), threading.Event()
    original = ImageEnhancer.process

    def blocked(self, data):
        if self.config.enable_sharpen:
            entered.set()
            assert release.wait(3)
        return original(self, data)

    monkeypatch.setattr(ImageEnhancer, 'process', blocked)
    local = EnhancementRuntime()
    local.configure(EnhancementConfig(enable_sharpen=True))

    async def scenario():
        preview.publish(jpeg(), 'image/jpeg', 'test')
        task = asyncio.create_task(local.run())
        try:
            deadline = time.monotonic()+3
            while not entered.is_set() and time.monotonic() < deadline:
                await asyncio.sleep(.005)
            assert entered.is_set()
            local.configure(EnhancementConfig(enable_sharpen=False))
            release.set()
            deadline = time.monotonic()+3
            while local.pair is None and time.monotonic() < deadline:
                await asyncio.sleep(.005)
            assert local.pair is not None
            assert len(local.samples) == 1
            assert len(local.finished) == 1
            assert local.stats()['dimensions'] == [160, 120]
            assert local.stats()['mean_receive_to_ready_ms'] >= 0
        finally:
            release.set()
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
    asyncio.run(scenario())


def test_lifespan_worker_matched_pair_and_metrics(monkeypatch,tmp_path):
    from app.camera_preview import CameraPreview
    import app.enhancement_runtime as worker
    preview=CameraPreview()
    local=EnhancementRuntime()
    monkeypatch.setattr(main,'runtime',local)
    monkeypatch.setattr(main,'preview',preview)
    monkeypatch.setattr(worker,'preview',preview)
    monkeypatch.setattr(main,'UPLOAD_DIR',tmp_path)
    import time
    with TestClient(main.app) as client:
        response=client.post('/camera/frame',files={'file':('frame.jpg',jpeg(),'image/jpeg')})
        assert response.status_code==200
        deadline=time.monotonic()+3
        while local.pair is None and time.monotonic()<deadline: time.sleep(.01)
        assert local.pair is not None
        original=client.get('/camera/preview/original')
        enhanced=client.get('/camera/preview/enhanced')
        assert original.headers['x-frame-sequence']==enhanced.headers['x-frame-sequence']
        assert original.content==jpeg()
        assert client.get('/camera/preview/original?sequence=99999').status_code==409
        assert client.get('/camera/performance').json()['processed_total']>=1


def test_worker_skips_backlog_and_keeps_latest(monkeypatch):
    import time
    import app.enhancement_runtime as worker
    from app.camera_preview import CameraPreview
    preview=CameraPreview()
    monkeypatch.setattr(worker,'preview',preview)
    original=ImageEnhancer.process

    def slow(self,data):
        time.sleep(.03)
        return original(self,data)

    monkeypatch.setattr(ImageEnhancer,'process',slow)
    local=EnhancementRuntime()

    async def scenario():
        task=asyncio.create_task(local.run())
        try:
            for _ in range(20):
                preview.publish(jpeg(),'image/jpeg','test')
                await asyncio.sleep(.001)
            deadline=time.monotonic()+3
            while (not local.pair or local.pair[0].sequence!=20) and time.monotonic()<deadline:
                await asyncio.sleep(.01)
            assert local.pair[0].sequence==20
            assert local.dropped>0
            assert local.processed<20
        finally:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
    asyncio.run(scenario())


def test_new_arrival_discards_in_flight_output(monkeypatch):
    import threading
    import time
    import app.enhancement_runtime as worker
    from app.camera_preview import CameraPreview
    preview = CameraPreview()
    monkeypatch.setattr(worker, 'preview', preview)
    entered, release = threading.Event(), threading.Event()
    original = ImageEnhancer.process
    calls = []

    def blocked(self, data):
        calls.append(data)
        if len(calls) == 1:
            entered.set()
            assert release.wait(3)
        return original(self, data)

    monkeypatch.setattr(ImageEnhancer, 'process', blocked)
    local = EnhancementRuntime()

    async def scenario():
        preview.publish(jpeg(50), 'image/jpeg', 'test')
        task = asyncio.create_task(local.run())
        try:
            deadline = time.monotonic() + 3
            while not entered.is_set() and time.monotonic() < deadline:
                await asyncio.sleep(.005)
            assert entered.is_set()
            preview.publish(jpeg(70), 'image/jpeg', 'test')
            preview.publish(jpeg(90), 'image/jpeg', 'test')
            assert local.pair is None
            release.set()
            deadline = time.monotonic() + 3
            while local.pair is None and time.monotonic() < deadline:
                await asyncio.sleep(.005)
            assert local.pair[0].sequence == 3
            assert local.dropped_after_processing == 1
            assert local.dropped == 1
            assert local.processed == 1 and len(local.samples) == 1
            assert calls == [jpeg(50), jpeg(90)]
        finally:
            release.set()
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
    asyncio.run(scenario())
