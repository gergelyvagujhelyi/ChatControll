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

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import DEBUG, TURN_ENABLED, TURN_RELAY_IP
from app.database import engine
from app.models.db import Base
from app.models.schemas import HealthResponse
from app.routers import calling, identity, messages, push, websocket

logging.basicConfig(
    level=logging.DEBUG if DEBUG else logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)


def _get_local_ip() -> str:
    """Get the machine's LAN IP address."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Create database tables on startup and start TURN server."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    turn_transport = None
    if TURN_ENABLED:
        from app.services.turn_server import start_turn_server
        try:
            turn_transport = await start_turn_server(relay_ip=TURN_RELAY_IP)
            app.state.turn_relay_ip = TURN_RELAY_IP
        except Exception as e:
            logging.getLogger(__name__).warning("TURN server failed to start: %s", e)

    yield

    if turn_transport:
        turn_transport.close()
    await engine.dispose()


app = FastAPI(
    title="ChatControll Relay",
    description="Privacy-first encrypted message relay server.",
    version="0.1.0",
    lifespan=lifespan,
    # Disable docs in production
    docs_url="/docs" if DEBUG else None,
    redoc_url=None,
)

# CORS — restrict in production
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"] if DEBUG else [],
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["*"],
)

# Register routers
app.include_router(identity.router)
app.include_router(messages.router)
app.include_router(push.router)
app.include_router(websocket.router)
app.include_router(calling.router)


@app.get("/health", response_model=HealthResponse, tags=["health"])
async def health_check() -> HealthResponse:
    return HealthResponse()
