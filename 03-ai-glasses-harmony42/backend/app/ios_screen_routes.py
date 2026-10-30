"""iOS 屏幕共享会话接口（独立命名空间 /api/ios/v1）。

不修改任何现有路由。屏幕共享与相机预览的关键区别：

- 现有 /camera/frame 的最新帧缓存是「共享内存状态」，不具备多用户隔离，
  不能直接作为 iOS 会话存储。
- 本模块使用 session_id 管理会话，并做基本鉴权（复用 VISION_API_TOKEN）。

隐私原则：
- 只保留「最近一帧」在内存，不做磁盘持久化，不做无界队列。
- 屏幕原图与敏感文本不写入日志。
- 会话可随时由客户端结束。

本阶段为预留/基础能力：仅支持单张截图分析（复用 /vision/analyze），
连续帧管理为可选增强，未实现时返回明确的 unsupported。
"""

import asyncio
import base64
import os
import re
import time
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.security import HTTPBearer
from pydantic import BaseModel, ConfigDict, Field

router = APIRouter(prefix="/api/ios/v1", tags=["iOS 屏幕共享"])


# --- 内存会话存储（单 worker，最近一帧，不做磁盘/数据库） ---

class ScreenSession:
    """单个屏幕共享会话，只保留最近一帧 JPEG 与元数据。"""

    def __init__(self, session_id: str):
        self.session_id = session_id
        self.created_at = time.monotonic()
        self.last_frame: Optional[bytes] = None
        self.last_frame_at: Optional[float] = None
        self.frame_count = 0
        self.finished = False


class _SessionRegistry:
    def __init__(self):
        self._sessions: dict[str, ScreenSession] = {}
        self._lock = asyncio.Lock()

    async def get_or_create(self, session_id: str) -> ScreenSession:
        async with self._lock:
            session = self._sessions.get(session_id)
            if session is None or session.finished:
                session = ScreenSession(session_id)
                self._sessions[session_id] = session
            return session

    async def get(self, session_id: str) -> Optional[ScreenSession]:
        async with self._lock:
            return self._sessions.get(session_id)

    async def latest(self) -> Optional[ScreenSession]:
        """返回最近上传过画面的会话。

        主 App 与广播扩展是两个进程，免费签名下没有 App Group 共享，
        主 App 无从得知扩展用了哪个 session_id。所以提供"全局最近一帧"入口，
        让主 App 能取回画面做本地 OCR。单 worker 场景下语义明确；
        多用户部署时必须按 device/account 隔离，否则会串数据（已知限制）。
        """
        async with self._lock:
            candidates = [s for s in self._sessions.values() if s.last_frame is not None]
            if not candidates:
                return None
            return max(candidates, key=lambda s: s.last_frame_at or 0.0)

    async def remove(self, session_id: str):
        async with self._lock:
            self._sessions.pop(session_id, None)

    async def prune(self, max_age_seconds: float):
        """清理超过 max_age 未活动的会话，避免无界增长。"""
        now = time.monotonic()
        async with self._lock:
            stale = [
                sid for sid, s in self._sessions.items()
                if now - (s.last_frame_at or s.created_at) > max_age_seconds
            ]
            for sid in stale:
                self._sessions.pop(sid, None)


registry = _SessionRegistry()

SESSION_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
MAX_FRAME_BYTES = 10 * 1024 * 1024


def _auth(request: Request):
    """复用 VISION_API_TOKEN 做 Bearer 鉴权（与 agent_routes 一致）。"""
    import secrets
    token = os.getenv("VISION_API_TOKEN", "").strip()
    if not token:
        raise HTTPException(status_code=503, detail="请配置 VISION_API_TOKEN 后使用 iOS 会话接口。")
    authorization = request.headers.get("authorization", "")
    scheme, _, supplied = authorization.partition(" ")
    if scheme.lower() != "bearer" or not secrets.compare_digest(supplied.encode(), token.encode()):
        raise HTTPException(status_code=401, detail="需要有效的访问凭证。")


# --- 请求/响应模型 ---

class CreateSessionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    session_id: str = Field(min_length=1, max_length=64)


class CreateSessionResponse(BaseModel):
    session_id: str
    status: str = "created"
    max_frame_bytes: int = MAX_FRAME_BYTES


class FrameUploadResponse(BaseModel):
    session_id: str
    success: bool
    frame_index: int


class AnalyzeSessionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    session_id: str = Field(min_length=1, max_length=64)


# --- 路由 ---

@router.post("/screen-session", response_model=CreateSessionResponse, summary="创建 iOS 屏幕共享会话")
async def create_session(body: CreateSessionRequest, request: Request):
    _auth(request)
    if not SESSION_ID_PATTERN.fullmatch(body.session_id):
        raise HTTPException(status_code=422, detail="session_id 格式无效。")
    session = await registry.get_or_create(body.session_id)
    return CreateSessionResponse(session_id=session.session_id)


@router.post("/screen-session/{session_id}/frame", response_model=FrameUploadResponse, summary="上传一帧屏幕画面")
async def upload_frame(session_id: str, request: Request):
    _auth(request)
    if not SESSION_ID_PATTERN.fullmatch(session_id):
        raise HTTPException(status_code=422, detail="session_id 格式无效。")
    session = await registry.get(session_id)
    if session is None or session.finished:
        raise HTTPException(status_code=404, detail="会话不存在或已结束。")

    content_type = (request.headers.get("content-type") or "").lower()
    if "image/jpeg" not in content_type:
        raise HTTPException(status_code=415, detail="仅支持 image/jpeg。")

    data = await request.body()
    if not data:
        raise HTTPException(status_code=400, detail="画面不能为空。")
    if len(data) > MAX_FRAME_BYTES:
        raise HTTPException(status_code=413, detail="画面不能超过 10 MiB。")
    if not data.startswith(b"\xff\xd8\xff") or not data.endswith(b"\xff\xd9"):
        raise HTTPException(status_code=415, detail="需要完整的 JPEG 画面。")

    # 只保留最近一帧，不落盘、不记日志
    session.last_frame = data
    session.last_frame_at = time.monotonic()
    session.frame_count += 1
    return FrameUploadResponse(session_id=session_id, success=True, frame_index=session.frame_count)


@router.post("/screen-session/{session_id}/analyze", summary="分析会话最近一帧（复用视觉模型）")
async def analyze_session_frame(session_id: str, request: Request):
    """复用 /vision/analyze 的视觉模型对最近一帧做基础分析。"""
    _auth(request)
    if not SESSION_ID_PATTERN.fullmatch(session_id):
        raise HTTPException(status_code=422, detail="session_id 格式无效。")
    session = await registry.get(session_id)
    if session is None or session.finished or session.last_frame is None:
        raise HTTPException(status_code=409, detail="会话没有可分析的画面。")

    # 复用 vision_service 的 analyze_vision（保持现有接口兼容）
    from app.vision_service import analyze_vision, VisionFailure
    try:
        return await analyze_vision(session.last_frame, "image/jpeg")
    except VisionFailure as exc:
        raise HTTPException(status_code=exc.http_status, detail=exc.message)


@router.delete("/screen-session/{session_id}", summary="结束 iOS 屏幕共享会话")
async def end_session(session_id: str, request: Request):
    _auth(request)
    if not SESSION_ID_PATTERN.fullmatch(session_id):
        raise HTTPException(status_code=422, detail="session_id 格式无效。")
    session = await registry.get(session_id)
    if session is not None:
        session.finished = True
        await registry.remove(session_id)
    return {"session_id": session_id, "status": "ended"}
