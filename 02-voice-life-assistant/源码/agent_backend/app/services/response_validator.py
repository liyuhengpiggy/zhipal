import json

from ..schemas.observation import ObservationRequest
from ..schemas.prediction import PredictionResponse


def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict:
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value


class ResponseValidator:
    def validate(self, raw: str | dict, observation: ObservationRequest) -> PredictionResponse:
        if isinstance(raw, str):
            if len(raw.encode("utf-8")) > 16_384:
                raise ValueError("prediction exceeds 16 KiB")
            raw = json.loads(raw, object_pairs_hook=reject_duplicate_keys)
        response = PredictionResponse.model_validate(raw)
        if response.request_id != observation.request_id or response.capture_id != observation.capture_id:
            raise ValueError("prediction IDs do not match request")
        selected = next((item for item in observation.candidates if item.candidate_id == response.decision.candidate_id), None)
        if selected is None or selected.action != response.decision.action:
            raise ValueError("unknown candidate or mismatched action")
        return response
