"""Windows local handoff: isolated environments, local secrets and health checks.

This bootstrap uses only the standard library. It never copies the sender's
environments, reads cloud credentials, changes the firewall or installs a service.
"""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import secrets
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
OCR_CONFIG = Path("config/ocr.local.json")
OCR_TEMPLATE = Path("config/ocr.local.example.json")
ENVIRONMENTS = {"backend": ".venv-backend", "ocr": ".venv-ocr"}


def write_new(path: Path, content: bytes) -> bool:
    """Create once; an existing recipient configuration always wins."""
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("xb") as stream:
            stream.write(content)
        return True
    except FileExistsError:
        return False


def initialize(root: Path) -> None:
    write_new(root / ".env", (root / ".env.example").read_bytes())
    if not (root / OCR_CONFIG).exists():
        config = json.loads((root / OCR_TEMPLATE).read_text(encoding="utf-8-sig"))
        config["api_token"] = secrets.token_urlsafe(32)
        write_new(root / OCR_CONFIG,
                  (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    load_ocr_config(root)
    print("Local settings ready. Existing settings were preserved; tokens are not printed.")


def load_ocr_config(root: Path) -> dict:
    try:
        config = json.loads((root / OCR_CONFIG).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        raise ValueError("Cannot read config/ocr.local.json. Run INSTALL_LOCAL.cmd or fix the JSON.") from None
    keys = {"host", "port", "api_token", "data_dir", "inference_timeout",
            "startup_timeout", "waiting_timeout", "cors_origins"}
    if not isinstance(config, dict) or set(config) != keys:
        raise ValueError("OCR config must contain exactly the keys shown in ocr.local.example.json.")
    try:
        ipaddress.IPv4Address(config["host"])
    except (ValueError, TypeError):
        raise ValueError("OCR host must be an IPv4 bind address, normally 0.0.0.0.") from None
    port = config["port"]
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("OCR port must be an integer between 1 and 65535.")
    token = config["api_token"]
    if not isinstance(token, str) or not token or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError("OCR api_token must be nonempty ASCII without whitespace.")
    directory = config["data_dir"]
    if not isinstance(directory, str) or not directory.strip():
        raise ValueError("OCR data_dir must be a nonempty path.")
    for key in ("inference_timeout", "startup_timeout", "waiting_timeout"):
        value = config[key]
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise ValueError(f"OCR {key} must be finite and positive.")
    origins = config["cors_origins"]
    if not isinstance(origins, list) or any(not isinstance(item, str) for item in origins):
        raise ValueError("OCR cors_origins must be a list of explicit HTTP(S) origins.")
    for origin in origins:
        try:
            parsed = urlsplit(origin)
            valid = (parsed.scheme in {"http", "https"} and parsed.hostname
                     and not (parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment)
                     and "*" not in origin and not any(c.isspace() for c in origin))
            parsed.port
        except ValueError:
            valid = False
        if not valid:
            raise ValueError("OCR cors_origins requires HTTP(S) origins without paths or wildcards.")
    return config


def clean_environment(environment=None) -> dict:
    original = os.environ if environment is None else environment
    result = {key: value for key, value in original.items()
              if key.upper() not in {"PYTHONHOME", "PYTHONPATH"}}
    result.update(PYTHONUTF8="1", PYTHONDONTWRITEBYTECODE="1")
    return result


def ocr_environment(root: Path, config: dict, environment=None) -> dict:
    # B's VISION_* names describe a cloud vendor. Never pass them to local OCR.
    result = {key: value for key, value in clean_environment(environment).items()
              if not key.upper().startswith(("VISION_", "AI_", "ARK_"))
              and key.upper() not in {"PORT", "CORS_ALLOW_ORIGINS", "UPLOAD_DIR",
                                      "UPLOAD_RETENTION_SECONDS", "UPLOAD_CLEANUP_INTERVAL_SECONDS"}}
    data_dir = Path(config["data_dir"])
    if not data_dir.is_absolute():
        data_dir = root / data_dir
    result.update(VISION_API_TOKEN=config["api_token"], VISION_DATA_DIR=str(data_dir.resolve()),
                  VISION_CORS_ALLOW_ORIGINS=",".join(config["cors_origins"]),
                  VISION_INFERENCE_TIMEOUT=str(config["inference_timeout"]),
                  VISION_STARTUP_TIMEOUT=str(config["startup_timeout"]),
                  VISION_WAITING_TIMEOUT=str(config["waiting_timeout"]),
                  PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK="True")
    return result


def python_path(root: Path, component: str) -> Path:
    return root / ENVIRONMENTS[component] / "Scripts" / "python.exe"


def require_environment(root: Path, component: str) -> Path:
    python = python_path(root, component)
    if not python.is_file() or not (python.parent.parent / "pyvenv.cfg").is_file():
        raise ValueError(f"Missing {ENVIRONMENTS[component]}; run INSTALL_LOCAL.cmd first.")
    return python


def verify_package(root: Path) -> dict:
    """Validate the outer package when present, without changing the OCR manifest."""
    path = root / "package_manifest.json"
    if not path.exists():
        return {"package_manifest": "source checkout (outer manifest not present)"}
    manifest = json.loads(path.read_text(encoding="utf-8"))
    rows = manifest["files"]
    seen = set()
    for row in rows:
        relative = row["path"]
        candidate = (root / relative).resolve()
        if (not candidate.is_relative_to(root.resolve()) or relative in seen
                or not candidate.is_file()):
            raise ValueError("Package contains a missing, duplicate or unsafe manifest path.")
        seen.add(relative)
        digest = hashlib.sha256()
        size = 0
        with candidate.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
                size += len(block)
        if size != row["bytes"] or digest.hexdigest() != row["sha256"]:
            raise ValueError("Package file checksum failed: " + relative)
    return {"verified_package_files": len(rows)}


def run_checked(command, root: Path) -> None:
    subprocess.run([str(item) for item in command], cwd=root, env=clean_environment(), check=True)


def verify(root: Path) -> None:
    print(json.dumps(verify_package(root), ensure_ascii=False))
    run_checked([sys.executable, "-B", root / "medicine-vision/verify_bundle.py"], root)


def install(root: Path, component: str) -> None:
    if sys.platform != "win32" or struct.calcsize("P") != 8 or sys.version_info[:2] != (3, 11):
        raise ValueError("Install requires Windows x64 and 64-bit Python 3.11. Install it, then rerun INSTALL_LOCAL.cmd.")
    if not str(root).isascii():
        raise ValueError("Extract to an English-only path such as C:\\AI-Glasses (Paddle native loader requirement).")
    verify(root)
    initialize(root)
    for name in ("backend", "ocr") if component == "all" else (component,):
        destination = root / ENVIRONMENTS[name]
        if not destination.exists():
            run_checked([sys.executable, "-m", "venv", destination], root)
        python = require_environment(root, name)
        probe = subprocess.run([str(python), "-B", "-c",
                                "import sys,struct; sys.exit(0 if sys.version_info[:2]==(3,11) and struct.calcsize('P')==8 else 1)"],
                               cwd=root, env=clean_environment(), check=False)
        if probe.returncode:
            raise ValueError(f"{ENVIRONMENTS[name]} is not Python 3.11 x64. Use a fresh extraction directory.")
        requirements = root / ("requirements.txt" if name == "backend" else "medicine-vision/requirements-api.txt")
        command = [python, "-m", "pip", "install", "--disable-pip-version-check", "-r", requirements]
        if name == "ocr":
            command.extend(["-c", root / "medicine-vision/constraints-windows-py311.txt"])
        run_checked(command, root)
        if name == "ocr":
            run_checked([python, "-B", root / "medicine-vision/patch_paddle_cache.py"], root)
        run_checked([python, "-m", "pip", "check"], root)
    print("Installation complete. Start START_BACKEND.cmd and START_OCR.cmd in separate windows.")
    print("OCR token: config/ocr.local.json (private; do not commit or include in a new handoff).")


def service_command(root: Path, component: str, config=None) -> tuple[list[str], Path, dict]:
    python = require_environment(root, component)
    if component == "backend":
        return [str(python), "-B", "-m", "app"], root, clean_environment()
    config = load_ocr_config(root) if config is None else config
    return ([str(python), "-B", "deploy_vision.py", "--host", config["host"], "--port", str(config["port"])],
            root / "medicine-vision", ocr_environment(root, config))


def start(root: Path, component: str) -> int:
    initialize(root)
    command, working_dir, environment = service_command(root, component)
    print(f"Starting {component}. Keep this window open; press Ctrl+C to stop.", flush=True)
    process = subprocess.Popen(command, cwd=working_dir, env=environment)
    try:
        return process.wait()
    except KeyboardInterrupt:
        # The console also delivers Ctrl+C to the child, allowing native cleanup.
        try:
            return process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.terminate()
            return process.wait(timeout=10)


def fetch_health(url: str) -> dict:
    # Local checks must not accidentally go through a system HTTP proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=2) as response:
        return json.load(response)


def check(root: Path, backend_port: int, wait_seconds: float) -> int:
    config = load_ocr_config(root)
    if config["port"] == backend_port:
        raise ValueError("Backend and OCR ports must differ. Defaults are 8000 and 8001.")
    host = "127.0.0.1" if config["host"] == "0.0.0.0" else config["host"]
    targets = {"backend": f"http://127.0.0.1:{backend_port}/health",
               "ocr": f"http://{host}:{config['port']}/health"}
    deadline = time.monotonic() + wait_seconds
    states = {}
    while True:
        for component, url in targets.items():
            try:
                health = fetch_health(url)
                ready = (health.get("status") == "ok" if component == "backend" else
                         health.get("ready") is True and health.get("capabilities", {}).get("ocr") is True)
                states[component] = {"url": url, "ready": ready}
                if component == "ocr":
                    states[component]["model_state"] = health.get("model", {}).get("state")
            except (OSError, ValueError, TypeError, AttributeError, urllib.error.URLError):
                states[component] = {"url": url, "ready": False, "error": "unavailable or invalid response"}
        if all(item["ready"] for item in states.values()) or time.monotonic() >= deadline:
            break
        time.sleep(1)
    print(json.dumps(states, ensure_ascii=False, indent=2))
    print("These are local checks. Also test /health from the phone/ESP32 LAN before acceptance.")
    return 0 if all(item["ready"] for item in states.values()) else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init", help="create local settings, preserving existing ones")
    installer = commands.add_parser("install", help="install Windows Python 3.11 environments")
    installer.add_argument("--component", choices=("all", "backend", "ocr"), default="all")
    commands.add_parser("verify", help="check package and original model checksums")
    commands.add_parser("backend", help="run JPEG/B service on its configured PORT")
    commands.add_parser("ocr", help="run local OCR using config/ocr.local.json")
    checker = commands.add_parser("check", help="check both local services without cloud calls")
    checker.add_argument("--backend-port", type=int, default=8000)
    checker.add_argument("--wait-seconds", type=float, default=0)
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            initialize(ROOT)
        elif args.command == "install":
            install(ROOT, args.component)
        elif args.command == "verify":
            verify(ROOT)
        elif args.command in {"backend", "ocr"}:
            return start(ROOT, args.command)
        elif args.command == "check":
            if not 1 <= args.backend_port <= 65535 or not math.isfinite(args.wait_seconds) or args.wait_seconds < 0:
                parser.error("Use a valid backend port and a finite, nonnegative wait time.")
            return check(ROOT, args.backend_port, args.wait_seconds)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        # Error strings originate in our validation or safe file/command paths, not credentials.
        print(f"Deployment failed: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
