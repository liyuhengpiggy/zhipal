import hmac
import json

from fastapi import APIRouter, HTTPException, Request
from pydantic import ValidationError

from ..schemas.observation import ObservationRequest
from ..schemas.prediction import PredictionResponse
from ..services.response_validator import reject_duplicate_keys

router = APIRouter()


@router.get("/health")
async def health(request: Request):
    return {"ok": True, "schema_version": 1, "provider": request.app.state.settings.provider}


@router.post("/api/v1/next-action/predict", response_model=PredictionResponse)
async def predict(request: Request) -> PredictionResponse:
    settings = request.app.state.settings
    if settings.api_token:
        expected = "Bearer " + settings.api_token
        actual = request.headers.get("authorization", "")
        if not hmac.compare_digest(actual.encode("utf-8"), expected.encode("utf-8")):
            raise HTTPException(status_code=401, detail="unauthorized")
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
        raise HTTPException(status_code=415, detail="application_json_required")
    try:
        payload = json.loads(await request.body(), object_pairs_hook=reject_duplicate_keys)
        observation = ObservationRequest.model_validate(payload)
    except (ValidationError, ValueError, UnicodeError, TypeError):
        # FastAPI/Pydantic default errors include input values. Return a fixed message instead.
        raise HTTPException(status_code=422, detail="invalid_observation") from None
    if request.app.state.prediction_capacity.locked():
        raise HTTPException(status_code=503, detail="prediction_capacity_exceeded")
    if not request.app.state.replay_guard.claim(observation.request_id):
        raise HTTPException(status_code=409, detail="request_id_already_seen_or_capacity_exceeded")
    async with request.app.state.prediction_capacity:
        return await request.app.state.predictor.predict(observation)
