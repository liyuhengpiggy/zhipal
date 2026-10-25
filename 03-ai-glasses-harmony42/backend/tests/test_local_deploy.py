import hashlib
import json

import pytest

from scripts import local_deploy


def local_config():
    return {
        "host": "0.0.0.0",
        "port": 8001,
        "api_token": "recipient-only-token",
        "data_dir": "medicine-vision/local_vision_data",
        "inference_timeout": 120,
        "startup_timeout": 180,
        "waiting_timeout": 600,
        "cors_origins": ["https://frontend.example.test"],
    }


def test_initialize_creates_random_token_and_preserves_local_settings(tmp_path, monkeypatch, capsys):
    (tmp_path / ".env.example").write_text("AI_MODE=mock\n", encoding="utf-8")
    (tmp_path / "config").mkdir()
    (tmp_path / local_deploy.OCR_TEMPLATE).write_bytes(
        (local_deploy.ROOT / local_deploy.OCR_TEMPLATE).read_bytes()
    )

    local_deploy.initialize(tmp_path)
    local_env = (tmp_path / ".env").read_text(encoding="utf-8")
    first = json.loads((tmp_path / local_deploy.OCR_CONFIG).read_text(encoding="utf-8"))
    assert local_env == "AI_MODE=mock\n"
    assert first["api_token"] and first["api_token"].isascii()
    assert len(first["api_token"]) >= 40
    assert first["port"] == 8001
    assert first["api_token"] not in capsys.readouterr().out

    (tmp_path / ".env").write_text("AI_API_KEY=existing-private-value\n", encoding="utf-8")
    first["api_token"] = "kept-token"
    (tmp_path / local_deploy.OCR_CONFIG).write_text(json.dumps(first), encoding="utf-8")
    local_deploy.initialize(tmp_path)
    assert (tmp_path / ".env").read_text(encoding="utf-8") == "AI_API_KEY=existing-private-value\n"
    assert json.loads((tmp_path / local_deploy.OCR_CONFIG).read_text(encoding="utf-8"))["api_token"] == "kept-token"


def test_ocr_environment_isolated_from_b_cloud_settings(tmp_path):
    config = local_config()
    environment = local_deploy.ocr_environment(tmp_path, config, {
        "AI_API_KEY": "do-not-inherit",
        "VISION_BASE_URL": "https://vendor.example.test",
        "ARK_API_KEY": "do-not-inherit-either",
        "PORT": "8000",
        "PYTHONPATH": "sender-local-path",
        "PATH": "path",
    })
    assert not {"AI_API_KEY", "VISION_BASE_URL", "ARK_API_KEY"} & environment.keys()
    assert environment["VISION_API_TOKEN"] == "recipient-only-token"
    assert environment["VISION_DATA_DIR"] == str(
        tmp_path / "medicine-vision/local_vision_data"
    )
    assert "PORT" not in environment
    assert "PYTHONPATH" not in environment
    assert "do-not-inherit" not in environment.values()


def test_local_config_rejects_wildcard_origin_and_port_collision(tmp_path):
    (tmp_path / "config").mkdir()
    config = local_config()
    config["cors_origins"] = ["*"]
    (tmp_path / local_deploy.OCR_CONFIG).write_text(json.dumps(config), encoding="utf-8")
    with pytest.raises(ValueError, match="origins"):
        local_deploy.load_ocr_config(tmp_path)

    config["cors_origins"] = []
    (tmp_path / local_deploy.OCR_CONFIG).write_text(json.dumps(config), encoding="utf-8")
    with pytest.raises(ValueError, match="ports must differ"):
        local_deploy.check(tmp_path, backend_port=8001, wait_seconds=0)


def test_package_manifest_verifies_content_and_rejects_traversal(tmp_path):
    data = b"delivery source"
    (tmp_path / "app").mkdir()
    (tmp_path / "app" / "main.py").write_bytes(data)
    (tmp_path / "package_manifest.json").write_text(json.dumps({
        "files": [{
            "path": "app/main.py",
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        }]
    }), encoding="utf-8")
    assert local_deploy.verify_package(tmp_path) == {"verified_package_files": 1}

    (tmp_path / "app" / "main.py").write_bytes(b"changed")
    with pytest.raises(ValueError, match="checksum"):
        local_deploy.verify_package(tmp_path)

    (tmp_path / "package_manifest.json").write_text(json.dumps({"files": [{
        "path": "../outside",
        "bytes": 0,
        "sha256": hashlib.sha256(b"").hexdigest(),
    }]}), encoding="utf-8")
    with pytest.raises(ValueError, match="unsafe"):
        local_deploy.verify_package(tmp_path)
