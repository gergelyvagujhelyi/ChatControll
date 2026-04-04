"""In-memory IP-based rate limiter for unauthenticated endpoints.

Protects bootstrap, key lookup, and share-code resolution from brute-force.
Uses a simple sliding-window counter per IP address with periodic cleanup.
"""

import asyncio
import time
from collections import defaultdict
from typing import NamedTuple

from fastapi import HTTPException, Request

# Defaults: 30 requests per minute per IP
IP_RATE_LIMIT = 30
IP_RATE_WINDOW = 60  # seconds


class _Bucket(NamedTuple):
    count: int
    window_start: float


_buckets: dict[str, _Bucket] = defaultdict(lambda: _Bucket(0, time.monotonic()))
_lock = asyncio.Lock()
_last_cleanup = time.monotonic()
_CLEANUP_INTERVAL = 300  # purge stale entries every 5 min


def _client_ip(request: Request) -> str:
    """Extract client IP, respecting X-Forwarded-For from a trusted proxy."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


async def check_ip_rate_limit(request: Request) -> None:
    """FastAPI dependency — raises 429 if the IP exceeds the rate limit."""
    ip = _client_ip(request)
    now = time.monotonic()

    async with _lock:
        # Periodic cleanup of stale buckets
        global _last_cleanup
        if now - _last_cleanup > _CLEANUP_INTERVAL:
            stale = [k for k, v in _buckets.items() if now - v.window_start > IP_RATE_WINDOW]
            for k in stale:
                del _buckets[k]
            _last_cleanup = now

        bucket = _buckets[ip]
        if now - bucket.window_start > IP_RATE_WINDOW:
            # New window
            _buckets[ip] = _Bucket(1, now)
            return

        if bucket.count >= IP_RATE_LIMIT:
            raise HTTPException(status_code=429, detail="Too many requests")

        _buckets[ip] = _Bucket(bucket.count + 1, bucket.window_start)
