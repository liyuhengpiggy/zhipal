from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from router import IntentRouter, MAX_TEXT_LENGTH
from intent_policy import POLICY_VERSION


@asynccontextmanager
async def lifespan(app):
    app.state.router = IntentRouter()
    yield


app = FastAPI(title="Chinese Intent Router", lifespan=lifespan)


class RouteRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_LENGTH)


@app.get("/health")
def health():
    router = app.state.router
    return {"status": "ready", "policy_version": POLICY_VERSION,
            "threshold": router.threshold, "margin": router.margin,
            "vision_task_threshold": router.vision_task_threshold,
            "vision_task_margin": router.vision_task_margin,
            "vision_tasks": ["read_text", "recognize_object", "recognize_environment"]}


@app.post("/route")
def route(request: RouteRequest):
    try:
        return app.state.router.route(request.text)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
