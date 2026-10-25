from abc import ABC, abstractmethod

from ..schemas.observation import ObservationRequest


class ProviderFailure(Exception):
    """Only a fixed code may escape to logs or callers, never upstream bodies."""

    def __init__(self, code: str):
        allowed = {"provider_timeout", "provider_response_too_large", "provider_invalid_response",
                   "provider_http_error", "provider_connection_error", "provider_not_completed",
                   "provider_unexpected_tool", "provider_refused"}
        self.code = code if code in allowed else "provider_failed"
        super().__init__(self.code)


class VisionModelProvider(ABC):
    @abstractmethod
    async def predict(self, observation: ObservationRequest, timeout_seconds: float) -> str | dict:
        """One decision attempt. Implementations must never retry automatically."""
        raise NotImplementedError
