"""WebSocket endpoint for real-time message delivery signals.

Clients authenticate with a signed token and receive JSON notifications when
new messages are available. Message content is never sent over WebSocket —
only a signal to fetch from the REST API.

Protocol:
- Client sends: {"type": "auth", "token": "<user_id>.<ts_ms>.<sig_b64>"}
- Server sends: {"type": "auth_ok"} on success
- Server sends: {"type": "new_message", "sender_id": "..."}
- Client sends: {"type": "ping"} periodically
- Server sends: {"type": "pong"}
"""

import asyncio
import json
import logging
import time
from collections import defaultdict
from typing import Optional

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_token
from app.config import WS_IDLE_TIMEOUT_SECONDS
from app.database import get_db
from app.models.db import Identity
from app.services.websocket_manager import ws_manager

router = APIRouter(tags=["websocket"])
logger = logging.getLogger(__name__)

_ALLOWED_SIGNAL_TYPES = frozenset({
    "call_offer", "call_answer", "call_ice_candidate",
    "call_hangup", "call_busy", "call_reject",
})
_MAX_CALL_OFFERS_PER_MINUTE = 10
_MAX_SIGNALS_PER_MINUTE = 100

# Per-user rate limit state shared across all WebSocket connections
_ws_signal_times: dict[str, list[float]] = defaultdict(list)
_ws_call_offer_times: dict[str, list[float]] = defaultdict(list)


@router.websocket("/v1/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    db: AsyncSession = Depends(get_db),
) -> None:
    """Handle a WebSocket connection for real-time delivery signals."""
    user_id: Optional[str] = None

    try:
        # Wait for auth message
        await websocket.accept()
        raw = await websocket.receive_text()
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Invalid JSON"})
            )
            await websocket.close(code=4002)
            return

        if msg.get("type") != "auth" or not msg.get("token"):
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Expected auth message with token"})
            )
            await websocket.close(code=4001)
            return

        token = msg["token"]

        # Verify signed auth token against stored public key
        parts = token.split(".", 2)
        if len(parts) != 3:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Authentication failed"})
            )
            await websocket.close(code=4003)
            return

        claimed_user_id = parts[0]
        result = await db.execute(
            select(Identity.public_signing_key).where(
                Identity.user_id == claimed_user_id
            )
        )
        pub_key_b64 = result.scalar_one_or_none()
        if pub_key_b64 is None:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Authentication failed"})
            )
            await websocket.close(code=4003)
            return

        try:
            user_id = verify_token(token, pub_key_b64)
        except ValueError as e:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Authentication failed"})
            )
            await websocket.close(code=4003)
            return

        # Register with the manager (accept was already called above)
        await ws_manager.register(user_id, websocket)
        logger.info("WebSocket authenticated: %s", user_id[:8])

        await websocket.send_text(
            json.dumps({"type": "auth_ok"})
        )

        # Keep alive loop — idle connections are closed after timeout
        while True:
            try:
                raw = await asyncio.wait_for(
                    websocket.receive_text(),
                    timeout=WS_IDLE_TIMEOUT_SECONDS,
                )
            except asyncio.TimeoutError:
                await websocket.close(code=4008, reason="Idle timeout")
                break
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_text(
                    json.dumps({"type": "error", "message": "Invalid JSON"})
                )
                continue

            msg_type = msg.get("type")

            if msg_type == "ping":
                await websocket.send_text(json.dumps({"type": "pong"}))

            elif msg_type in _ALLOWED_SIGNAL_TYPES:
                recipient_id = msg.get("recipient_id", "")
                call_id = msg.get("call_id", "")
                encrypted_payload = msg.get("encrypted_payload", "")
                signature = msg.get("signature", "")

                # Validate types and lengths (match REST schema constraints)
                if (
                    not isinstance(recipient_id, str)
                    or not isinstance(call_id, str)
                    or not isinstance(encrypted_payload, str)
                    or not isinstance(signature, str)
                    or not recipient_id
                    or not call_id
                    or len(recipient_id) > 64
                    or len(call_id) > 128
                    or len(encrypted_payload) > 65536
                    or len(signature) > 512
                ):
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": "Invalid call signal"})
                    )
                    continue

                # Prevent self-calls
                if recipient_id == user_id:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": "Invalid call signal"})
                    )
                    continue

                # Per-user signal rate limit (shared across all connections)
                now_sig = time.monotonic()
                # Periodically prune stale entries (idle > 5 min)
                if len(_ws_signal_times) > 1000:
                    stale = [uid for uid, ts in _ws_signal_times.items()
                             if not ts or (now_sig - ts[-1]) > 300]
                    for uid in stale:
                        _ws_signal_times.pop(uid, None)
                        _ws_call_offer_times.pop(uid, None)
                sig_times = _ws_signal_times[user_id]
                sig_times[:] = [t for t in sig_times if now_sig - t < 60]
                if len(sig_times) >= _MAX_SIGNALS_PER_MINUTE:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": "Rate limit exceeded"})
                    )
                    continue
                sig_times.append(now_sig)

                # Per-user call_offer rate limit
                if msg_type == "call_offer":
                    now = time.monotonic()
                    offer_times = _ws_call_offer_times[user_id]
                    offer_times[:] = [t for t in offer_times if now - t < 60]
                    if len(offer_times) >= _MAX_CALL_OFFERS_PER_MINUTE:
                        await websocket.send_text(
                            json.dumps({"type": "error", "message": "Rate limit exceeded"})
                        )
                        continue
                    offer_times.append(now)

                await ws_manager.relay_call_signal(
                    sender_id=user_id,
                    recipient_id=recipient_id,
                    signal_type=msg_type,
                    call_id=call_id,
                    encrypted_payload=encrypted_payload,
                    signature=signature,
                )

    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("WebSocket error for user %s", user_id[:8] if user_id else "unknown")
    finally:
        if user_id:
            await ws_manager.disconnect(user_id, websocket)
            # Clean up rate limit entries if user has no more connections
            if not ws_manager.is_online(user_id):
                _ws_signal_times.pop(user_id, None)
                _ws_call_offer_times.pop(user_id, None)
