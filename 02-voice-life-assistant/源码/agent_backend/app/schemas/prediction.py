from pydantic import Field

from .observation import Action, Identifier, StrictModel, UuidString


class Decision(StrictModel):
    action: Action
    candidate_id: Identifier
    confidence: float = Field(ge=0, le=1)
    reason: str = Field(min_length=1, max_length=300)


class PredictionResponse(StrictModel):
    schema_version: int = Field(ge=1, le=1)
    request_id: UuidString
    capture_id: UuidString
    decision: Decision
