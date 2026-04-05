"""WebSocket connection manager for real-time message delivery.

Each connected client registers with their user_id. When a new message
arrives for a user, the server pushes a notification through the WebSocket.

Privacy note: WebSocket messages contain only a generic delivery signal —
never message content or sender identity. The client then fetches and
decrypts the envelope via the REST API.
"""

import asyncio
import json
import logging
import time
from collections import defaultdict, OrderedDict
from typing import Dict

from fastapi import WebSocket

logger = logging.getLogger(__name__)


MAX_WS_CONNECTIONS_PER_USER = 5


_PENDING_SIGNAL_TTL = 30  # seconds — signals older than this are discarded


class WebSocketManager:
    """Manages active WebSocket connections per user."""

    def __init__(self) -> None:
        # user_id → insertion-ordered dict of active WebSocket connections
        # Using OrderedDict (instead of set) so eviction removes the oldest.
        self._connections: Dict[str, OrderedDict[WebSocket, None]] = defaultdict(OrderedDict)
        self._lock = asyncio.Lock()
        # Pending call signals for users who are not currently connected.
        # user_id → list of (timestamp, payload_json) tuples.
        self._pending_call_signals: Dict[str, list[tuple[float, str]]] = {}

    async def connect(self, user_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        await self.register(user_id, websocket)

    async def register(self, user_id: str, websocket: WebSocket) -> None:
        """Register an already-accepted WebSocket, enforcing per-user cap.

        NOTE: Does NOT flush pending signals — the caller must send auth_ok
        first, then call flush_pending_signals(). Otherwise the client
        receives a call signal where it expects auth_ok and disconnects.
        """
        async with self._lock:
            if len(self._connections[user_id]) >= MAX_WS_CONNECTIONS_PER_USER:
                # Evict the oldest (first-inserted) connection
                oldest, _ = self._connections[user_id].popitem(last=False)
                try:
                    await oldest.close(code=4008, reason="Too many connections")
                except Exception:
                    pass
            self._connections[user_id][websocket] = None
        logger.info("WebSocket connected: %s", user_id[:8])

    async def disconnect(self, user_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            self._connections[user_id].pop(websocket, None)
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
            sockets = list(self._connections.get(recipient_id, {}))

        if not sockets:
            return False

        payload = json.dumps({
            "type": "new_message",
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

    async def shutdown(self) -> None:
        """Gracefully close all connections (called on server shutdown)."""
        async with self._lock:
            all_sockets = [
                (uid, ws)
                for uid, conns in self._connections.items()
                for ws in conns
            ]
            self._connections.clear()

        for uid, ws in all_sockets:
            try:
                await ws.close(code=1012, reason="Server shutting down")
            except Exception:
                pass
        logger.info("All WebSocket connections closed for shutdown")

    async def flush_pending_signals(self, user_id: str, websocket: WebSocket) -> None:
        """Deliver pending call signals to a newly connected user."""
        now = time.monotonic()
        signals = self._pending_call_signals.pop(user_id, [])
        for ts, payload in signals:
            if now - ts > _PENDING_SIGNAL_TTL:
                continue  # expired
            try:
                await websocket.send_text(payload)
                logger.info("Delivered pending call signal to %s", user_id[:8])
            except Exception:
                pass

    def _store_pending_signal(self, recipient_id: str, payload: str) -> None:
        """Buffer a call signal for a user who is currently offline."""
        now = time.monotonic()
        pending = self._pending_call_signals.setdefault(recipient_id, [])
        # Evict expired entries
        pending[:] = [(ts, p) for ts, p in pending if now - ts <= _PENDING_SIGNAL_TTL]
        # Cap at a reasonable number to prevent abuse
        if len(pending) < 20:
            pending.append((now, payload))

    async def relay_call_signal(
        self,
        sender_id: str,
        recipient_id: str,
        signal_type: str,
        call_id: str,
        encrypted_payload: str,
        signature: str = "",
    ) -> bool:
        """Relay an opaque encrypted call signal to the recipient.

        Returns True if at least one WebSocket received the signal.
        """
        async with self._lock:
            sockets = list(self._connections.get(recipient_id, {}))

        payload = json.dumps({
            "type": signal_type,
            "sender_id": sender_id,
            "call_id": call_id,
            "encrypted_payload": encrypted_payload,
            "signature": signature,
        })

        if not sockets:
            # Recipient offline — buffer the signal for delivery when they connect
            self._store_pending_signal(recipient_id, payload)
            return False

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
