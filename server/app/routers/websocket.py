"""WebSocket endpoint for real-time message delivery signals.

Clients connect with their user_id and receive JSON notifications when
new messages are available. Message content is never sent over WebSocket —
only a signal to fetch from the REST API.

Protocol:
- Client sends: {"type": "auth", "user_id": "..."}
- Server sends: {"type": "new_message", "sender_id": "..."}
- Client sends: {"type": "ping"} periodically
- Server sends: {"type": "pong"}
"""

import json
import logging
from typing import Optional

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlalchemy import select

from app.database import async_session
from app.models.db import Identity
from app.services.websocket_manager import ws_manager

router = APIRouter(tags=["websocket"])
logger = logging.getLogger(__name__)


@router.websocket("/v1/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
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

        if msg.get("type") != "auth" or not msg.get("user_id"):
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Expected auth message"})
            )
            await websocket.close(code=4001)
            return

        user_id = msg["user_id"]

        # Verify identity exists in the database
        async with async_session() as db:
            result = await db.execute(
                select(Identity.user_id).where(Identity.user_id == user_id)
            )
            if result.scalar_one_or_none() is None:
                await websocket.send_text(
                    json.dumps({"type": "error", "message": "Unknown identity"})
                )
                await websocket.close(code=4003)
                return

        # Register with the manager (accept was already called above)
        await ws_manager.register(user_id, websocket)
        logger.info("WebSocket authenticated: %s", user_id[:8])

        await websocket.send_text(
            json.dumps({"type": "auth_ok"})
        )

        # Keep alive loop
        while True:
            raw = await websocket.receive_text()
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
