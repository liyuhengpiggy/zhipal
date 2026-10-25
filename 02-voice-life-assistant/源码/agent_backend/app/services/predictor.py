"""A prediction ends here: success or a safe NO_OP, without another model call."""

import asyncio
import json
import logging
from time import monotonic

from pydantic import ValidationError

from ..providers.base import ProviderFailure, VisionModelProvider
from ..schemas.observation import ObservationRequest
from ..schemas.prediction import PredictionResponse
from .response_validator import ResponseValidator

LOGGER = logging.getLogger("next_action")


class NextActionPredictor:
    def __init__(self, provider: VisionModelProvider, timeout_seconds: float = 15,
                 validator: ResponseValidator | None = None, debug_logging: bool = False):
        self._provider = provider
        self._timeout_seconds = timeout_seconds
        self._validator = validator or ResponseValidator()
        self._debug_logging = debug_logging

    async def predict(self, observation: ObservationRequest) -> PredictionResponse:
        started = monotonic()
        outcome = "ok"
        try:
            task = asyncio.create_task(self._provider.predict(observation, self._timeout_seconds))
            try:
                done, _ = await asyncio.wait({task}, timeout=self._timeout_seconds)
                if not done:
                    task.cancel()
                    # Never await a misbehaving provider that suppresses cancellation.
                    task.add_done_callback(self._discard_late_result)
                    raise asyncio.TimeoutError
                raw = task.result()
            except asyncio.CancelledError:
                task.cancel()
                task.add_done_callback(self._discard_late_result)
                raise
            result = self._validator.validate(raw, observation)
        except asyncio.CancelledError:
            # A disconnected/cancelled request may never use a late result.
            raise
        except asyncio.TimeoutError:
            outcome = "prediction_timeout"
            result = self._no_op(observation, "模型请求超时，本次不执行。")
        except ProviderFailure as error:
            outcome = error.code
            result = self._no_op(observation, "视觉服务未返回有效决策，本次不执行。")
        except (ValidationError, ValueError, TypeError, KeyError):
            outcome = "invalid_prediction"
            result = self._no_op(observation, "模型响应未通过候选与协议校验，本次不执行。")
        except Exception:
            # Do not log exception repr/traceback: upstream errors may contain prompts or keys.
            outcome = "prediction_failed"
            result = self._no_op(observation, "预测服务异常，本次不执行。")
        metadata = {
            "event": "next_action_prediction", "request_id": observation.request_id,
            "outcome": outcome, "elapsed_ms": round((monotonic() - started) * 1000),
        }
        if self._debug_logging:
            metadata.update(node_count=len(observation.ui_nodes), candidate_count=len(observation.candidates),
                            history_count=len(observation.history))
        LOGGER.info(json.dumps(metadata, separators=(",", ":")))
        return result

    @staticmethod
    def _discard_late_result(task: asyncio.Task) -> None:
        if not task.cancelled():
            # Retrieve exceptions without logging upstream text or consuming its decision.
            task.exception()

    @staticmethod
    def _no_op(observation: ObservationRequest, reason: str) -> PredictionResponse:
        no_op = next(item for item in observation.candidates if item.action == "NO_OP")
        return PredictionResponse.model_validate({
            "schema_version": 1, "request_id": observation.request_id, "capture_id": observation.capture_id,
            "decision": {"action": "NO_OP", "candidate_id": no_op.candidate_id,
                         "confidence": 0.0, "reason": reason},
        })
