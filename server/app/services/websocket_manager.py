"""WebSocket connection manager for real-time message delivery.

Each connected client registers with their user_id. When a new message
arrives for a user, the server pushes a notification through the WebSocket
in addition to (or instead of) FCM push.

Privacy note: WebSocket messages contain only a delivery signal with
sender_id — never message content. The client then fetches and decrypts
the envelope via the REST API.
"""

import asyncio
import json
import logging
from collections import defaultdict
from typing import Dict, Set

from fastapi import WebSocket

logger = logging.getLogger(__name__)


MAX_WS_CONNECTIONS_PER_USER = 5


class WebSocketManager:
    """Manages active WebSocket connections per user."""

    def __init__(self) -> None:
        # user_id → set of active WebSocket connections
        self._connections: Dict[str, Set[WebSocket]] = defaultdict(set)
        self._lock = asyncio.Lock()

    async def connect(self, user_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        async with self._lock:
            if len(self._connections[user_id]) >= MAX_WS_CONNECTIONS_PER_USER:
                # Evict the oldest connection
                oldest = next(iter(self._connections[user_id]))
                self._connections[user_id].discard(oldest)
                try:
                    await oldest.close(code=4008, reason="Too many connections")
                except Exception:
                    pass
            self._connections[user_id].add(websocket)
        logger.info("WebSocket connected: %s", user_id[:8])

    async def disconnect(self, user_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            self._connections[user_id].discard(websocket)
            if not self._connections[user_id]:
                del self._connections[user_id]
        logger.info("WebSocket disconnected: %s", user_id[:8])

    def is_online(self, user_id: str) -> bool:
        # Use get() with explicit default to avoid defaultdict creating empty entries
        return bool(self._connections.get(user_id, None))

    async def notify_new_message(
        self,
        recipient_id: str,
        sender_id: str,
    ) -> bool:
        """Push a new-message signal to the recipient if they're connected.

        Returns True if at least one WebSocket was notified.
        """
        async with self._lock:
            sockets = list(self._connections.get(recipient_id, set()))

        if not sockets:
            return False

        payload = json.dumps({
            "type": "new_message",
            "sender_id": sender_id,
        })

        notified = False
        for ws in sockets:
            try:
                await ws.send_text(payload)
                notified = True
            except Exception:
                # Connection may have died; will be cleaned up on next ping
                pass

        return notified

    async def relay_call_signal(
        self,
        sender_id: str,
        recipient_id: str,
        signal_type: str,
        call_id: str,
        encrypted_payload: str,
    ) -> bool:
        """Relay an opaque encrypted call signal to the recipient.

        Returns True if at least one WebSocket received the signal.
        """
        async with self._lock:
            sockets = list(self._connections.get(recipient_id, set()))

        if not sockets:
            return False

        payload = json.dumps({
            "type": signal_type,
            "sender_id": sender_id,
            "call_id": call_id,
            "encrypted_payload": encrypted_payload,
        })

        delivered = False
        for ws in sockets:
            try:
                await ws.send_text(payload)
                delivered = True
            except Exception:
                pass

        return delivered


# Singleton instance
ws_manager = WebSocketManager()
