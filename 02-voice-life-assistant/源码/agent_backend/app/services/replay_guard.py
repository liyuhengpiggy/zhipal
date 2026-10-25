"""Bounded in-memory HTTP replay rejection; Android still enforces execution identity."""

from collections import OrderedDict
from threading import Lock
from time import monotonic


class ReplayGuard:
    def __init__(self, capacity: int = 2048, ttl_seconds: float = 600):
        self._seen: OrderedDict[str, float] = OrderedDict()
        self._capacity = capacity
        self._ttl = ttl_seconds
        self._lock = Lock()

    def claim(self, request_id: str) -> bool:
        with self._lock:
            now = monotonic()
            while self._seen and next(iter(self._seen.values())) <= now - self._ttl:
                self._seen.popitem(last=False)
            if request_id in self._seen or len(self._seen) >= self._capacity:
                return False
            self._seen[request_id] = now
            return True
