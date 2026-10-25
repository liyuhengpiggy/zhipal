"""Environment configuration; secrets are never included in representations."""

from dataclasses import dataclass, field
import math
import os


@dataclass(frozen=True)
class Settings:
    provider: str = "openai"
    model: str = ""
    api_key: str = field(default="", repr=False)
    request_timeout_seconds: float = 15.0
    enable_debug_logging: bool = False
    api_token: str = field(default="", repr=False)

    def __post_init__(self) -> None:
        if self.provider not in {"mock", "openai"}:
            raise ValueError("VISION_PROVIDER must be mock or openai")
        if not math.isfinite(self.request_timeout_seconds) or not 0.05 <= self.request_timeout_seconds <= 120:
            raise ValueError("REQUEST_TIMEOUT_SECONDS must be between 0.05 and 120")
        if self.provider == "openai" and (not self.model.strip() or not self.api_key.strip()):
            raise ValueError("VISION_MODEL and OPENAI_API_KEY are required for openai")

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            provider=os.getenv("VISION_PROVIDER", "openai").strip().lower(),
            model=os.getenv("VISION_MODEL", "").strip(),
            api_key=os.getenv("OPENAI_API_KEY", "").strip(),
            request_timeout_seconds=float(os.getenv("REQUEST_TIMEOUT_SECONDS", "15")),
            enable_debug_logging=os.getenv("ENABLE_DEBUG_LOGGING", "false").lower() in {"1", "true", "yes"},
            api_token=os.getenv("AGENT_API_TOKEN", "").strip(),
        )
