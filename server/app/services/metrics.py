"""Lightweight Prometheus-compatible metrics.

Exposes request counts, latencies, and active WebSocket connections
at ``/metrics`` in Prometheus text exposition format.

No external dependencies — uses stdlib only.
"""

import secrets
import time
from collections import defaultdict
from threading import Lock

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse, PlainTextResponse

from app.config import METRICS_TOKEN


class Metrics:
    def __init__(self) -> None:
        self._lock = Lock()
        self._request_count: dict[str, int] = defaultdict(int)
        self._request_errors: dict[str, int] = defaultdict(int)
        self._latency_sum: dict[str, float] = defaultdict(float)
        self.active_ws_connections = 0

    def record(self, method: str, path: str, status: int, duration: float) -> None:
        key = f'{method} {path}'
        with self._lock:
            self._request_count[key] += 1
            self._latency_sum[key] += duration
            if status >= 400:
                self._request_errors[key] += 1

    def expose(self) -> str:
        lines = []
        with self._lock:
            lines.append("# HELP http_requests_total Total HTTP requests")
            lines.append("# TYPE http_requests_total counter")
            for key, count in sorted(self._request_count.items()):
                method, path = key.split(" ", 1)
                lines.append(f'http_requests_total{{method="{method}",path="{path}"}} {count}')

            lines.append("# HELP http_request_errors_total HTTP requests with 4xx/5xx status")
            lines.append("# TYPE http_request_errors_total counter")
            for key, count in sorted(self._request_errors.items()):
                method, path = key.split(" ", 1)
                lines.append(f'http_request_errors_total{{method="{method}",path="{path}"}} {count}')

            lines.append("# HELP http_request_duration_seconds_sum Total request processing time")
            lines.append("# TYPE http_request_duration_seconds_sum counter")
            for key, total in sorted(self._latency_sum.items()):
                method, path = key.split(" ", 1)
                lines.append(f'http_request_duration_seconds_sum{{method="{method}",path="{path}"}} {total:.6f}')

            lines.append("# HELP ws_active_connections Current WebSocket connections")
            lines.append("# TYPE ws_active_connections gauge")
            lines.append(f"ws_active_connections {self.active_ws_connections}")

        return "\n".join(lines) + "\n"


metrics = Metrics()


class MetricsMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if request.url.path == "/metrics":
            return await call_next(request)

        start = time.monotonic()
        response = await call_next(request)
        duration = time.monotonic() - start

        # Normalize paths to avoid cardinality explosion
        path = request.url.path
        for prefix in ("/v1/identity/", "/v1/messages/", "/v1/calls/", "/v1/push/"):
            if path.startswith(prefix):
                # Keep the first segment after prefix, strip IDs
                rest = path[len(prefix):]
                if "/" in rest:
                    path = prefix + "{id}" + rest[rest.index("/"):]
                break

        metrics.record(request.method, path, response.status_code, duration)
        return response


def metrics_endpoint(request: Request) -> PlainTextResponse:
    if METRICS_TOKEN:
        auth = request.headers.get("authorization", "")
        if not auth.startswith("Bearer ") or not secrets.compare_digest(
            auth[7:], METRICS_TOKEN
        ):
            return JSONResponse(status_code=401, content={"detail": "Unauthorized"})
    return PlainTextResponse(metrics.expose(), media_type="text/plain; version=0.0.4")
