"""Firebase Cloud Messaging push notification service.

Sends wake-up signals only — never includes message content in the push payload.
The client fetches encrypted envelopes directly from this server after wake-up.
"""

import asyncio
import logging
from typing import Optional

from app.config import FIREBASE_CREDENTIALS

logger = logging.getLogger(__name__)

_firebase_initialized = False


def _init_firebase() -> bool:
    """Lazily initialize Firebase Admin SDK."""
    global _firebase_initialized
    if _firebase_initialized:
        return True
    if not FIREBASE_CREDENTIALS:
        logger.info("FIREBASE_CREDENTIALS not set — push notifications disabled")
        return False
    try:
        import firebase_admin
        from firebase_admin import credentials

        cred = credentials.Certificate(FIREBASE_CREDENTIALS)
        firebase_admin.initialize_app(cred)
        _firebase_initialized = True
        logger.info("Firebase Admin SDK initialized")
        return True
    except Exception:
        logger.exception("Failed to initialize Firebase Admin SDK")
        return False


async def _clear_stale_token(recipient_id: str) -> None:
    """Remove the FCM token for a user whose token is no longer valid."""
    try:
        from sqlalchemy import select

        from app.database import async_session
        from app.models.db import Identity

        async with async_session() as db:
            result = await db.execute(
                select(Identity).where(Identity.user_id == recipient_id)
            )
            identity = result.scalar_one_or_none()
            if identity and identity.fcm_token:
                identity.fcm_token = None
                await db.commit()
                logger.info("Cleared stale FCM token for %s", recipient_id[:8])
    except Exception:
        logger.exception("Failed to clear stale FCM token for %s", recipient_id[:8])


async def send_push_notification(
    fcm_token: str,
    recipient_id: Optional[str] = None,
    sender_id: Optional[str] = None,
) -> bool:
    """Send a wake-up push to a device.

    The payload contains only the sender ID for notification routing — never
    any message content. The client fetches the encrypted envelope from the
    /v1/messages/pending endpoint.

    If the FCM token is invalid/expired, it is automatically cleared from
    the database so future sends fall back to polling.
    """
    if not _init_firebase():
        return False

    try:
        from firebase_admin import messaging

        data = {"type": "new_message"}
        if sender_id:
            data["senderId"] = sender_id

        message = messaging.Message(
            data=data,
            token=fcm_token,
            android=messaging.AndroidConfig(
                priority="high",
                ttl=60,
            ),
        )
        await asyncio.to_thread(messaging.send, message)
        return True
    except Exception as e:
        error_name = type(e).__name__
        # Firebase raises specific errors for invalid tokens
        if error_name in ("UnregisteredError", "InvalidArgumentError", "SenderIdMismatchError"):
            logger.warning("FCM token invalid (%s) for %s — clearing",
                           error_name, (recipient_id or "unknown")[:8])
            if recipient_id:
                await _clear_stale_token(recipient_id)
        else:
            logger.exception("Failed to send push notification")
        return False
