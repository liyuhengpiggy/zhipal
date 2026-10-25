from ..schemas.observation import ObservationRequest
from .base import VisionModelProvider


class MockVisionProvider(VisionModelProvider):
    async def predict(self, observation: ObservationRequest, timeout_seconds: float) -> dict:
        no_op = next(item for item in observation.candidates if item.action == "NO_OP")
        return {
            "schema_version": 1,
            "request_id": observation.request_id,
            "capture_id": observation.capture_id,
            "decision": {"action": "NO_OP", "candidate_id": no_op.candidate_id,
                         "confidence": 1.0, "reason": "服务端 Mock 模式：不执行手机操作。"},
        }
