"""REST endpoints for voice call signaling and ICE server configuration."""

import base64
import hashlib
import hmac
import time
from collections import defaultdict

from fastapi import APIRouter, Depends, HTTPException, Request

from app.auth import verify_auth_token
from app.models.schemas import (
    CallSignalRequest,
    CallSignalResponse,
    IceServer,
    IceServersResponse,
)
from app.config import TURN_CREDENTIAL_TTL, TURN_SECRET
from app.services.websocket_manager import ws_manager

router = APIRouter(prefix="/v1/calls", tags=["calling"])

_ALLOWED_SIGNAL_TYPES = frozenset({
    "call_offer", "call_answer", "call_ice_candidate",
    "call_hangup", "call_busy", "call_reject",
})

_MAX_SIGNALS_PER_MINUTE = 100
_signal_times: dict[str, list[float]] = defaultdict(list)


@router.post("/signal", response_model=CallSignalResponse)
async def relay_signal(
    request: CallSignalRequest,
    x_user_id: str = Depends(verify_auth_token),
) -> CallSignalResponse:
    if request.signal_type not in _ALLOWED_SIGNAL_TYPES:
        raise HTTPException(status_code=400, detail="Invalid signal type")
    if request.recipient_id == x_user_id:
        raise HTTPException(status_code=400, detail="Cannot signal self")

    # Per-user rate limit
    now = time.monotonic()
    times = _signal_times[x_user_id]
    times[:] = [t for t in times if now - t < 60]
    if len(times) >= _MAX_SIGNALS_PER_MINUTE:
        raise HTTPException(status_code=429, detail="Rate limit exceeded")
    times.append(now)
    delivered = await ws_manager.relay_call_signal(
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        signal_type=request.signal_type,
        call_id=request.call_id,
        encrypted_payload=request.encrypted_payload,
        signature=request.signature,
    )
    return CallSignalResponse(delivered=delivered)


@router.get("/ice-servers", response_model=IceServersResponse)
async def get_ice_servers(
    request: Request,
    user_id: str = Depends(verify_auth_token),
) -> IceServersResponse:
    """Return ICE server configuration for WebRTC calls."""
    servers = [
        IceServer(urls="stun:stun.l.google.com:19302"),
    ]

    relay_ip = getattr(request.app.state, "turn_relay_ip", None)
    if relay_ip and TURN_SECRET:
        # Ephemeral credentials (coturn --use-auth-secret compatible)
        expiry = int(time.time()) + TURN_CREDENTIAL_TTL
        username = f"{expiry}:{user_id}"
        credential = base64.b64encode(
            hmac.new(
                TURN_SECRET.encode(), username.encode(), hashlib.sha1
            ).digest()
        ).decode()

        servers.append(
            IceServer(
                urls=f"turn:{relay_ip}:3478?transport=udp",
                username=username,
                credential=credential,
            )
        )

    return IceServersResponse(ice_servers=servers)
