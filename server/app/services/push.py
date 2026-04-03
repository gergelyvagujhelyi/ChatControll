"""Firebase Cloud Messaging push notification service.

Sends wake-up signals only — never includes message content in the push payload.
The client fetches encrypted envelopes directly from this server after wake-up.
"""

import logging

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


async def send_push_notification(
    fcm_token: str,
    sender_id: str,
    conversation_id: str,
) -> bool:
    """Send a wake-up push to a device.

    The payload contains only the sender ID and a conversation hint —
    never any message content. The client fetches the encrypted envelope
    from the /v1/messages/pending endpoint.
    """
    if not _init_firebase():
        return False

    try:
        from firebase_admin import messaging

        message = messaging.Message(
            data={
                "type": "new_message",
                "senderId": sender_id,
                # Conversation ID helps the client deep-link, but is not sensitive
                # since the server already knows sender→recipient routing.
                "conversationId": conversation_id,
            },
            token=fcm_token,
            android=messaging.AndroidConfig(
                priority="high",
                ttl=60,  # seconds — stale pushes are useless
            ),
        )
        messaging.send(message)
        return True
    except Exception:
        logger.exception("Failed to send push notification")
        return False
