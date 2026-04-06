"""In-memory IP-based rate limiter for unauthenticated endpoints.

Protects bootstrap, key lookup, and share-code resolution from brute-force.
Uses a simple sliding-window counter per IP address with periodic cleanup.

WARNING: This rate limiter is per-process and stored in memory only.
In multi-worker deployments (e.g. multiple uvicorn workers), each worker
maintains its own counters, effectively multiplying the allowed rate by
the number of workers. To compensate, the per-worker limit is divided by
the number of workers (set via UVICORN_WORKERS env var, default 1).
For large-scale setups, use a shared store (Redis, database) instead.
"""

import asyncio
import os
import time
from collections import defaultdict
from typing import NamedTuple

from fastapi import HTTPException, Request

from app.config import TRUSTED_PROXIES

_WORKER_COUNT = max(1, int(os.getenv("UVICORN_WORKERS", "1")))
# Integer division rounds down intentionally — the effective global limit
# (per-worker * workers) is slightly below the configured value, which is
# conservative.  E.g. 30 // 4 = 7 per worker → 28 effective vs 30 configured.
IP_RATE_LIMIT = max(1, int(os.getenv("IP_RATE_LIMIT", "30")) // _WORKER_COUNT)
IP_RATE_WINDOW = 60  # seconds


class _Bucket(NamedTuple):
    count: int
    window_start: float


_MAX_BUCKETS = 100_000
_buckets: dict[str, _Bucket] = defaultdict(lambda: _Bucket(0, time.monotonic()))
_lock = asyncio.Lock()
_last_cleanup = time.monotonic()
_CLEANUP_INTERVAL = 300  # purge stale entries every 5 min


def _client_ip(request: Request) -> str:
    """Extract client IP, honoring X-Forwarded-For only from trusted proxies.

    Uses the rightmost IP not in TRUSTED_PROXIES, which is the last hop
    the proxy chain can vouch for. The leftmost IP is client-controlled
    and trivially spoofable.
    """
    direct_ip = request.client.host if request.client else "unknown"
    if direct_ip in TRUSTED_PROXIES:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            ips = [ip.strip() for ip in forwarded.split(",")]
            # Walk from right to left; return the first IP not in our trusted set
            for ip in reversed(ips):
                if ip not in TRUSTED_PROXIES:
                    return ip
    return direct_ip


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

        # Cap dictionary size to prevent memory exhaustion
        if len(_buckets) >= _MAX_BUCKETS and ip not in _buckets:
            stale = [k for k, v in _buckets.items() if now - v.window_start > IP_RATE_WINDOW]
            for k in stale:
                del _buckets[k]
            if len(_buckets) >= _MAX_BUCKETS:
                raise HTTPException(status_code=429, detail="Too many requests")

        bucket = _buckets[ip]
        if now - bucket.window_start > IP_RATE_WINDOW:
            # New window
            _buckets[ip] = _Bucket(1, now)
            return

        if bucket.count >= IP_RATE_LIMIT:
            raise HTTPException(status_code=429, detail="Too many requests")

        _buckets[ip] = _Bucket(bucket.count + 1, bucket.window_start)
