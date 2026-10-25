"""Versioned wire protocol. UI text and pixels are untrusted input."""

import base64
import binascii
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator, model_validator

MAX_REQUEST_BYTES = 2 * 1024 * 1024
MAX_JPEG_BYTES = 1024 * 1024
Identifier = Annotated[str, StringConstraints(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_.:-]+$")]
UuidString = Annotated[str, StringConstraints(pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")]
Action = Literal["TAP", "SCROLL_UP", "SCROLL_DOWN", "BACK", "NO_OP"]
ShortLabel = Annotated[str, StringConstraints(max_length=200)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class Bounds(StrictModel):
    # Hidden/non-target Android nodes may have empty or off-screen rectangles.
    # They remain observation data; only candidates require an actionable rectangle.
    left: int = Field(ge=-100_000, le=100_000)
    top: int = Field(ge=-100_000, le=100_000)
    right: int = Field(ge=-100_000, le=100_000)
    bottom: int = Field(ge=-100_000, le=100_000)

    def actionable(self) -> bool:
        return self.left >= 0 and self.top >= 0 and self.right > self.left and self.bottom > self.top


def jpeg_dimensions(data: bytes) -> tuple[int, int]:
    """Inspect bounded JPEG headers, without allocating decoded pixels."""
    if len(data) < 12 or data[:2] != b"\xff\xd8" or data[-2:] != b"\xff\xd9":
        raise ValueError("screenshot must be a JPEG with SOI/EOI markers")
    index = 2
    while index < len(data) - 2:
        if data[index] != 0xFF:
            raise ValueError("invalid JPEG header")
        while index < len(data) and data[index] == 0xFF:
            index += 1
        if index >= len(data):
            break
        marker = data[index]
        index += 1
        if marker in {0x01, *range(0xD0, 0xD8)}:
            continue
        if marker in {0xD8, 0xD9, 0xDA} or index + 2 > len(data):
            break
        size = int.from_bytes(data[index:index + 2], "big")
        if size < 2 or index + size > len(data):
            raise ValueError("invalid JPEG segment length")
        if marker in {0xC0, 0xC1, 0xC2}:
            if size < 8:
                raise ValueError("invalid JPEG frame")
            height = int.from_bytes(data[index + 3:index + 5], "big")
            width = int.from_bytes(data[index + 5:index + 7], "big")
            return width, height
        index += size
    raise ValueError("JPEG has no supported frame header")


class Screenshot(StrictModel):
    mime_type: Literal["image/jpeg"]
    width: int = Field(ge=1, le=4096)
    height: int = Field(ge=1, le=4096)
    jpeg_base64: str = Field(min_length=16, max_length=4 * ((MAX_JPEG_BYTES + 2) // 3))

    @model_validator(mode="after")
    def valid_jpeg(self) -> "Screenshot":
        try:
            data = base64.b64decode(self.jpeg_base64, validate=True)
        except (ValueError, binascii.Error) as error:
            raise ValueError("screenshot is not valid base64") from error
        if len(data) > MAX_JPEG_BYTES:
            raise ValueError("JPEG exceeds 1 MiB")
        if jpeg_dimensions(data) != (self.width, self.height):
            raise ValueError("JPEG dimensions do not match metadata")
        return self


class UiNode(StrictModel):
    node_key: Identifier
    class_name: str = Field(max_length=200)
    view_id: str | None = Field(max_length=300)
    label: ShortLabel
    bounds: Bounds
    visible: bool
    enabled: bool
    clickable: bool
    scrollable: bool


class HistoryEvent(StrictModel):
    sequence: int = Field(ge=0)
    occurred_at_ms: int = Field(ge=0)
    event_type: str = Field(min_length=1, max_length=80)
    package_name: str = Field(min_length=1, max_length=255)
    window_id: int
    label: ShortLabel


class Candidate(StrictModel):
    candidate_id: Identifier
    action: Action
    target_label: ShortLabel
    node_key: Identifier | None
    class_name: str | None = Field(max_length=200)
    view_id: str | None = Field(max_length=300)
    bounds: Bounds | None


class ObservationRequest(StrictModel):
    schema_version: int = Field(ge=1, le=1)
    request_id: UuidString
    capture_id: UuidString
    package_name: str = Field(min_length=1, max_length=255)
    window_id: int
    captured_at_ms: int = Field(ge=0)
    ui_fingerprint: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
    screenshot: Screenshot
    ui_nodes: list[UiNode] = Field(min_length=1, max_length=160)
    history: list[HistoryEvent] = Field(max_length=20)
    candidates: list[Candidate] = Field(min_length=1, max_length=40)

    @field_validator("request_id", "capture_id")
    @classmethod
    def valid_uuid(cls, value: str) -> str:
        UUID(value)
        return value

    @model_validator(mode="after")
    def candidates_belong_to_snapshot(self) -> "ObservationRequest":
        if self.request_id == self.capture_id:
            raise ValueError("request_id and capture_id must be distinct")
        nodes = {node.node_key: node for node in self.ui_nodes}
        if len(nodes) != len(self.ui_nodes):
            raise ValueError("duplicate node_key")
        if len({item.candidate_id for item in self.candidates}) != len(self.candidates):
            raise ValueError("duplicate candidate_id")
        if sum(item.action == "NO_OP" for item in self.candidates) != 1:
            raise ValueError("exactly one NO_OP candidate is required")
        for item in self.candidates:
            if item.action in {"NO_OP", "BACK"}:
                if any(value is not None for value in (item.node_key, item.class_name, item.view_id, item.bounds)):
                    raise ValueError("global actions must not have node targets")
                continue
            node = nodes.get(item.node_key)
            if node is None or not node.visible or not node.enabled:
                raise ValueError("candidate target is absent or unavailable")
            if not node.bounds.actionable():
                raise ValueError("candidate target has invalid bounds")
            if (item.class_name, item.view_id, item.bounds) != (node.class_name, node.view_id, node.bounds):
                raise ValueError("candidate target does not match snapshot node")
            if item.action == "TAP" and not node.clickable:
                raise ValueError("TAP target must be clickable")
            if item.action in {"SCROLL_UP", "SCROLL_DOWN"} and not node.scrollable:
                raise ValueError("scroll target must be scrollable")
        return self
