"""Run from agent_backend: python -m uvicorn app.main:app --host 127.0.0.1 --port 8788."""

import asyncio
import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .api.body_limit import BodyLimitMiddleware
from .api.routes import router
from .config import Settings
from .providers.base import VisionModelProvider
from .providers.mock import MockVisionProvider
from .providers.openai_responses import OpenAIResponsesProvider
from .services.predictor import NextActionPredictor
from .services.replay_guard import ReplayGuard


def create_app(settings: Settings | None = None, provider: VisionModelProvider | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    application = FastAPI(title="NextAction single-step prediction", version="1.0.0",
                          docs_url=None, redoc_url=None, openapi_url=None)
    if provider is None:
        provider = MockVisionProvider() if settings.provider == "mock" else OpenAIResponsesProvider(settings.model, settings.api_key)
    application.state.settings = settings
    application.state.replay_guard = ReplayGuard()
    application.state.prediction_capacity = asyncio.Semaphore(4)
    application.state.predictor = NextActionPredictor(
        provider, settings.request_timeout_seconds, debug_logging=settings.enable_debug_logging,
    )
    application.add_middleware(BodyLimitMiddleware)
    application.include_router(router)

    @application.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError):
        return JSONResponse(status_code=422, content={"detail": "invalid_observation"})

    @application.exception_handler(Exception)
    async def unexpected_failure(request: Request, exc: Exception):
        logging.getLogger("next_action").error('{"event":"http_error","outcome":"internal_error"}')
        return JSONResponse(status_code=500, content={"detail": "internal_error"})

    return application


app = create_app()
