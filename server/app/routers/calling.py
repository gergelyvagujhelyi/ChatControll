"""REST endpoints for voice call signaling and ICE server configuration."""

import asyncio
import base64
import hashlib
import hmac
import time
from collections import defaultdict

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import Identity
from app.models.schemas import (
    CallSignalRequest,
    CallSignalResponse,
    IceServer,
    IceServersResponse,
)
from app.config import TURN_CREDENTIAL_TTL, TURN_SECRET
from app.services.push import send_push_notification
from app.services.websocket_manager import ws_manager

router = APIRouter(prefix="/v1/calls", tags=["calling"])

_ALLOWED_SIGNAL_TYPES = frozenset({
    "call_offer", "call_answer", "call_ice_candidate",
    "call_hangup", "call_busy", "call_reject",
})

_MAX_SIGNALS_PER_MINUTE = 100
_signal_lock = asyncio.Lock()
_signal_times: dict[str, list[float]] = defaultdict(list)
_signal_last_prune: float = 0.0
_SIGNAL_HIGH_WATER = 2000
_SIGNAL_PRUNE_INTERVAL = 60  # seconds between prune attempts


@router.post("/signal", response_model=CallSignalResponse)
async def relay_signal(
    request: CallSignalRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> CallSignalResponse:
    if request.signal_type not in _ALLOWED_SIGNAL_TYPES:
        raise HTTPException(status_code=400, detail="Invalid signal type")
    if request.recipient_id == x_user_id:
        raise HTTPException(status_code=400, detail="Cannot signal self")

    # Per-user rate limit — lock to prevent interleaved read-modify-write
    async with _signal_lock:
        now = time.monotonic()
        times = _signal_times[x_user_id]
        times[:] = [t for t in times if now - t < 60]
        if len(times) >= _MAX_SIGNALS_PER_MINUTE:
            raise HTTPException(status_code=429, detail="Rate limit exceeded")
        times.append(now)

        # Prune stale entries when high-water mark is hit,
        # but at most once per _SIGNAL_PRUNE_INTERVAL.
        global _signal_last_prune
        if (len(_signal_times) > _SIGNAL_HIGH_WATER
                and now - _signal_last_prune > _SIGNAL_PRUNE_INTERVAL):
            _signal_last_prune = now
            stale = [uid for uid, ts in _signal_times.items()
                     if not ts or (now - ts[-1]) > 300]
            for uid in stale:
                del _signal_times[uid]

    delivered = await ws_manager.relay_call_signal(
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        signal_type=request.signal_type,
        call_id=request.call_id,
        encrypted_payload=request.encrypted_payload,
        signature=request.signature,
        kem_ciphertext=request.kem_ciphertext,
    )

    # FCM push fallback for call_offer — wake the recipient's app so it
    # connects WebSocket and picks up the buffered signal.
    if not delivered and request.signal_type == "call_offer":
        result = await db.execute(
            select(Identity.fcm_token).where(
                Identity.user_id == request.recipient_id
            )
        )
        fcm_token = result.scalar_one_or_none()
        if fcm_token:
            await send_push_notification(
                fcm_token=fcm_token,
                recipient_id=request.recipient_id,
                sender_id=x_user_id,
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
