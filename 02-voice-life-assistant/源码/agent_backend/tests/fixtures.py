from copy import deepcopy

from app.schemas.observation import ObservationRequest

JPEG = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD3+iiigD//2Q=="


def payload() -> dict:
    bounds = {"left": 0, "top": 0, "right": 16, "bottom": 16}
    return {
        "schema_version": 1,
        "request_id": "8c26260a-81d0-41f4-9695-6c0fb50aca31",
        "capture_id": "4a173b59-906f-48c1-a7a4-6036c7a80603",
        "package_name": "example.practice", "window_id": 5,
        "captured_at_ms": 1720000000000, "ui_fingerprint": "a" * 64,
        "screenshot": {"mime_type": "image/jpeg", "width": 16, "height": 16, "jpeg_base64": JPEG},
        "ui_nodes": [{"node_key": "node-1", "class_name": "android.widget.Button", "view_id": "id/practice",
                      "label": "下一页", "bounds": bounds, "visible": True, "enabled": True,
                      "clickable": True, "scrollable": True}],
        "history": [{"sequence": 1, "occurred_at_ms": 1719999999900, "event_type": "TYPE_VIEW_CLICKED",
                     "package_name": "example.practice", "window_id": 5, "label": "练习"}],
        "candidates": [
            {"candidate_id": "tap-1", "action": "TAP", "target_label": "下一页", "node_key": "node-1",
             "class_name": "android.widget.Button", "view_id": "id/practice", "bounds": deepcopy(bounds)},
            {"candidate_id": "scroll-1", "action": "SCROLL_DOWN", "target_label": "向下滚动", "node_key": "node-1",
             "class_name": "android.widget.Button", "view_id": "id/practice", "bounds": deepcopy(bounds)},
            {"candidate_id": "back-1", "action": "BACK", "target_label": "返回", "node_key": None,
             "class_name": None, "view_id": None, "bounds": None},
            {"candidate_id": "none-1", "action": "NO_OP", "target_label": "不操作", "node_key": None,
             "class_name": None, "view_id": None, "bounds": None},
        ],
    }


def observation() -> ObservationRequest:
    return ObservationRequest.model_validate(payload())


def prediction(candidate_id: str = "tap-1", action: str = "TAP") -> dict:
    value = payload()
    return {"schema_version": 1, "request_id": value["request_id"], "capture_id": value["capture_id"],
            "decision": {"action": action, "candidate_id": candidate_id, "confidence": 0.9, "reason": "继续查看。"}}
