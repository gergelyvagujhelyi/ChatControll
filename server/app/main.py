"""ChatControll Relay Server

A minimal, privacy-first relay server that:
- Stores public key bundles for contact discovery
- Routes encrypted envelopes between clients
- Sends FCM wake-up signals for offline delivery
- Provides WebSocket connections for real-time delivery
- Deletes envelopes after recipient acknowledgement
- Rate-limits senders to prevent abuse

The server NEVER:
- Sees plaintext message content
- Stores persistent message logs
- Requires personally identifiable information
- Uploads contact books or social graphs
"""

import logging
import socket
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from app.config import CORS_ORIGINS, DEBUG, MAX_REQUEST_BODY_BYTES, METRICS_TOKEN, TURN_ENABLED, TURN_RELAY_IP, TURN_SECRET
from sqlalchemy import text

from app.database import async_session, engine
from app.models.schemas import HealthResponse
from app.routers import calling, identity, messages, push, websocket
from app.services.metrics import MetricsMiddleware, metrics_endpoint
from app.services.websocket_manager import ws_manager

if DEBUG:
    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
else:
    # Structured JSON logging for production (one JSON object per line)
    import json as _json

    class _JsonFormatter(logging.Formatter):
        def format(self, record: logging.LogRecord) -> str:
            return _json.dumps({
                "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
                "level": record.levelname,
                "logger": record.name,
                "msg": record.getMessage(),
                **({"exc": self.formatException(record.exc_info)} if record.exc_info else {}),
            })

    _handler = logging.StreamHandler()
    _handler.setFormatter(_JsonFormatter())
    logging.basicConfig(level=logging.INFO, handlers=[_handler])


def _get_local_ip() -> str:
    """Get the machine's LAN IP address."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


async def _run_alembic_upgrade() -> None:
    """Run Alembic migrations to bring the database schema up to date.

    This is the single source of truth for schema management — both
    in local development and production (Docker entrypoint also runs
    ``alembic upgrade head``; running it here is idempotent).

    Runs in a thread because Alembic's ``command.upgrade`` calls
    ``asyncio.run()`` internally for async engines, which conflicts
    with the already-running event loop.
    """
    import asyncio
    import os

    def _upgrade():
        from alembic.config import Config
        from alembic import command
        from sqlalchemy import inspect, create_engine

        alembic_cfg = Config(os.path.join(os.path.dirname(__file__), "..", "alembic.ini"))
        alembic_cfg.set_main_option("script_location",
            os.path.join(os.path.dirname(__file__), "..", "migrations"))
        from app.config import DATABASE_URL
        alembic_cfg.set_main_option("sqlalchemy.url", DATABASE_URL)

        # If tables already exist (e.g. from create_all in tests), stamp head
        # so Alembic doesn't try to recreate them. Only do this when the
        # alembic_version table is absent (i.e. Alembic has never run).
        sync_url = DATABASE_URL.replace("+aiosqlite", "").replace("+asyncpg", "+psycopg2")
        sync_engine = create_engine(sync_url)
        try:
            inspector = inspect(sync_engine)
            tables = inspector.get_table_names()
            has_alembic = "alembic_version" in tables
            has_app_tables = "identities" in tables
        finally:
            sync_engine.dispose()

        if has_app_tables and not has_alembic:
            log = logging.getLogger(__name__)
            log.info("Tables exist without alembic_version; stamping head")
            command.stamp(alembic_cfg, "head")
            return

        command.upgrade(alembic_cfg, "head")

    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, _upgrade)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start services and run database migrations."""
    await _run_alembic_upgrade()

    turn_transport = None
    if TURN_ENABLED:
        if not TURN_SECRET:
            logging.getLogger(__name__).error(
                "TURN_ENABLED=true but TURN_SECRET is not set. "
                "Set TURN_SECRET or disable TURN with TURN_ENABLED=false."
            )
        else:
            from app.services.turn_server import start_turn_server
            try:
                turn_transport = await start_turn_server(relay_ip=TURN_RELAY_IP)
                app.state.turn_relay_ip = TURN_RELAY_IP
            except Exception as e:
                logging.getLogger(__name__).warning("TURN server failed to start: %s", e)

    yield

    await ws_manager.shutdown()
    if turn_transport:
        turn_transport.close()
    await engine.dispose()


app = FastAPI(
    title="ChatControll Relay",
    description="Privacy-first encrypted message relay server.",
    version="0.3.5",
    lifespan=lifespan,
    # Disable docs in production
    docs_url="/docs" if DEBUG else None,
    redoc_url=None,
)

class BodySizeLimitMiddleware:
    """ASGI middleware that rejects requests whose body exceeds the limit.

    Checks Content-Length header when present, and also wraps the ASGI
    receive channel to count bytes as they stream in — protecting against
    chunked-encoded requests that omit Content-Length.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(
            (k.lower(), v)
            for k, v in (scope.get("headers") or [])
        )
        content_length = headers.get(b"content-length")
        if content_length is not None:
            try:
                cl = int(content_length)
            except (ValueError, TypeError):
                response = JSONResponse(status_code=400, content={"detail": "Invalid Content-Length"})
                await response(scope, receive, send)
                return
            if cl < 0 or cl > MAX_REQUEST_BODY_BYTES:
                response = JSONResponse(status_code=413, content={"detail": "Request body too large"})
                await response(scope, receive, send)
                return

        # Wrap receive to track cumulative body size for chunked requests
        total_bytes = 0
        response_started = False

        class _BodyTooLarge(Exception):
            pass

        async def counting_receive():
            nonlocal total_bytes
            msg = await receive()
            if msg["type"] == "http.request":
                total_bytes += len(msg.get("body", b""))
                if total_bytes > MAX_REQUEST_BODY_BYTES:
                    raise _BodyTooLarge()
            return msg

        async def tracking_send(message):
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, counting_receive, tracking_send)
        except _BodyTooLarge:
            if not response_started:
                response = JSONResponse(status_code=413, content={"detail": "Request body too large"})
                await response(scope, receive, send)


app.add_middleware(BodySizeLimitMiddleware)
app.add_middleware(MetricsMiddleware)

# CORS — restrict in production
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"] if DEBUG else CORS_ORIGINS,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["*"],
)

# Register routers
app.include_router(identity.router)
app.include_router(messages.router)
app.include_router(push.router)
app.include_router(websocket.router)
app.include_router(calling.router)


if DEBUG or METRICS_TOKEN:
    app.add_api_route("/metrics", metrics_endpoint, methods=["GET"], tags=["ops"], include_in_schema=False)


@app.get("/health", response_model=HealthResponse, tags=["health"])
async def health_check() -> HealthResponse:
    try:
        async with async_session() as db:
            await db.execute(text("SELECT 1"))
    except Exception:
        return JSONResponse(
            status_code=503,
            content={"status": "unhealthy", "version": app.version, "detail": "Database unreachable"},
        )
    return HealthResponse()
