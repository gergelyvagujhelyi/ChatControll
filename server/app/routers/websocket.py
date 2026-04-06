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

# Per-user rate limit state shared across all WebSocket connections.
# Protected by _ws_rate_lock to prevent interleaved read-modify-write
# across concurrent WebSocket handlers.
_ws_rate_lock = asyncio.Lock()
_ws_signal_times: dict[str, list[float]] = defaultdict(list)
_ws_call_offer_times: dict[str, list[float]] = defaultdict(list)
_ws_rate_last_prune: float = 0.0
_WS_RATE_HIGH_WATER = 2000
_WS_RATE_PRUNE_INTERVAL = 60  # seconds between prune attempts


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
        # Explicitly close the DB session after auth to release the connection
        # back to the pool. Without this, the session stays open for the entire
        # WebSocket lifetime (potentially hours), exhausting the pool.
        # NOTE: db is unusable after this point — do not add DB operations below.
        await db.close()
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

        # Flush pending call signals AFTER auth_ok so the client doesn't
        # mistake a buffered signal for a failed auth response.
        await ws_manager.flush_pending_signals(user_id, websocket)

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
                kem_ciphertext = msg.get("kem_ciphertext", "")

                # Validate types and lengths (match REST schema constraints)
                if (
                    not isinstance(recipient_id, str)
                    or not isinstance(call_id, str)
                    or not isinstance(encrypted_payload, str)
                    or not isinstance(signature, str)
                    or not isinstance(kem_ciphertext, str)
                    or not recipient_id
                    or not call_id
                    or len(recipient_id) > 64
                    or len(call_id) > 128
                    or len(encrypted_payload) > 65536
                    or len(signature) > 512
                    or len(kem_ciphertext) > 2048
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

                # Per-user signal rate limit (shared across all connections).
                # Check-and-update under lock; send error response outside.
                rate_limited = False
                async with _ws_rate_lock:
                    now_sig = time.monotonic()
                    # Prune stale entries when high-water mark is hit,
                    # but at most once per _WS_RATE_PRUNE_INTERVAL.
                    global _ws_rate_last_prune
                    if (len(_ws_signal_times) > _WS_RATE_HIGH_WATER
                            and now_sig - _ws_rate_last_prune > _WS_RATE_PRUNE_INTERVAL):
                        _ws_rate_last_prune = now_sig
                        stale = [uid for uid, ts in _ws_signal_times.items()
                                 if not ts or (now_sig - ts[-1]) > 300]
                        for uid in stale:
                            _ws_signal_times.pop(uid, None)
                            _ws_call_offer_times.pop(uid, None)
                    sig_times = _ws_signal_times[user_id]
                    sig_times[:] = [t for t in sig_times if now_sig - t < 60]
                    if len(sig_times) >= _MAX_SIGNALS_PER_MINUTE:
                        rate_limited = True
                    else:
                        sig_times.append(now_sig)
                        # Per-user call_offer rate limit
                        if msg_type == "call_offer":
                            offer_times = _ws_call_offer_times[user_id]
                            offer_times[:] = [t for t in offer_times if now_sig - t < 60]
                            if len(offer_times) >= _MAX_CALL_OFFERS_PER_MINUTE:
                                rate_limited = True
                            else:
                                offer_times.append(now_sig)

                if rate_limited:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": "Rate limit exceeded"})
                    )
                    continue

                await ws_manager.relay_call_signal(
                    sender_id=user_id,
                    recipient_id=recipient_id,
                    signal_type=msg_type,
                    call_id=call_id,
                    encrypted_payload=encrypted_payload,
                    signature=signature,
                    kem_ciphertext=kem_ciphertext,
                )

    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("WebSocket error for user %s", user_id[:8] if user_id else "unknown")
    finally:
        if user_id:
            await ws_manager.disconnect(user_id, websocket)
            # Rate limit state is preserved for the TTL duration even after disconnect
            # to prevent quota reset via reconnect. Stale entries are pruned periodically.
