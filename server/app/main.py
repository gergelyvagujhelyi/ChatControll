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
from starlette.middleware.base import BaseHTTPMiddleware

from app.config import CORS_ORIGINS, DEBUG, MAX_REQUEST_BODY_BYTES, METRICS_TOKEN, TURN_ENABLED, TURN_RELAY_IP, TURN_SECRET
from sqlalchemy import text

from app.database import async_session, engine
from app.models.db import Base
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


async def _migrate_pending_messages(conn) -> None:
    """Add columns that may be missing from an older pending_messages table.

    SQLAlchemy's create_all only creates new tables — it never ALTERs
    existing ones. This lightweight migration adds columns introduced
    after the initial schema so that existing deployments keep working.
    """
    log = logging.getLogger(__name__)
    migrations = [
        ("ephemeral_public_key", "ALTER TABLE pending_messages ADD COLUMN ephemeral_public_key TEXT NOT NULL DEFAULT ''"),
        ("signature", "ALTER TABLE pending_messages ADD COLUMN signature TEXT NOT NULL DEFAULT ''"),
        ("timestamp_ms", "ALTER TABLE pending_messages ADD COLUMN timestamp_ms BIGINT"),
    ]
    for col_name, ddl in migrations:
        try:
            await conn.execute(text(ddl))
            log.info("Migrated pending_messages: added column %s", col_name)
        except Exception:
            # Column already exists — expected on fresh or already-migrated DBs
            pass


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start services and ensure database tables exist."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        await _migrate_pending_messages(conn)

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
    version="0.3.2",
    lifespan=lifespan,
    # Disable docs in production
    docs_url="/docs" if DEBUG else None,
    redoc_url=None,
)

class BodySizeLimitMiddleware(BaseHTTPMiddleware):
    """Reject HTTP requests whose body exceeds the configured limit.

    Checks Content-Length header when present, then also verifies the
    actual body size for chunked/streamed requests.
    """

    async def dispatch(self, request: Request, call_next):
        content_length = request.headers.get("content-length")
        if content_length is not None:
            try:
                cl = int(content_length)
            except (ValueError, TypeError):
                return JSONResponse(status_code=400, content={"detail": "Invalid Content-Length"})
            if cl < 0 or cl > MAX_REQUEST_BODY_BYTES:
                return JSONResponse(status_code=413, content={"detail": "Request body too large"})
        # For chunked requests (no Content-Length), check actual body size
        if content_length is None and request.method in ("POST", "PUT", "PATCH"):
            body = await request.body()
            if len(body) > MAX_REQUEST_BODY_BYTES:
                return JSONResponse(status_code=413, content={"detail": "Request body too large"})
        return await call_next(request)


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
            content={"status": "unhealthy", "version": "0.3.2", "detail": "Database unreachable"},
        )
    return HealthResponse()
