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
                json.dumps({"type": "error", "message": "Malformed token"})
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
                json.dumps({"type": "error", "message": "Unknown identity"})
            )
            await websocket.close(code=4003)
            return

        try:
            user_id = verify_token(token, pub_key_b64)
        except ValueError as e:
            await websocket.send_text(
                json.dumps({"type": "error", "message": str(e)})
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

            elif msg_type in (
                "call_offer",
                "call_answer",
                "call_ice_candidate",
                "call_hangup",
                "call_busy",
                "call_reject",
            ):
                recipient_id = msg.get("recipient_id", "")
                call_id = msg.get("call_id", "")
                encrypted_payload = msg.get("encrypted_payload", "")
                if recipient_id and call_id:
                    await ws_manager.relay_call_signal(
                        sender_id=user_id,
                        recipient_id=recipient_id,
                        signal_type=msg_type,
                        call_id=call_id,
                        encrypted_payload=encrypted_payload,
                    )

    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("WebSocket error for user %s", user_id[:8] if user_id else "unknown")
    finally:
        if user_id:
            await ws_manager.disconnect(user_id, websocket)
