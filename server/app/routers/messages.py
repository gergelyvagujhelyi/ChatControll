"""Message endpoints: send, fetch pending, acknowledge.

The server is a relay: it stores encrypted envelopes temporarily and
delivers them to the recipient. It cannot read message content.
"""

import uuid
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import MAX_PENDING_MESSAGES_PER_USER
from app.database import get_db
from app.models.db import Identity, PendingMessage
from app.models.schemas import (
    AckRequest,
    PendingMessageResponse,
    SendMessageRequest,
    SendMessageResponse,
)
from app.services.push import send_push_notification
from app.services.rate_limiter import check_rate_limit
from app.services.websocket_manager import ws_manager

router = APIRouter(prefix="/v1/messages", tags=["messages"])


@router.post("/send", response_model=SendMessageResponse)
async def send_message(
    request: SendMessageRequest,
    x_user_id: str = Header(..., alias="X-User-Id"),
    db: AsyncSession = Depends(get_db),
) -> SendMessageResponse:
    """Submit an encrypted envelope for relay to the recipient.

    The server:
    1. Rate-checks the sender.
    2. Stores the encrypted envelope.
    3. Attempts real-time delivery via WebSocket.
    4. Falls back to FCM push if the recipient is offline.

    The server never inspects or logs the encrypted body.
    """
    # Verify sender is a registered identity
    sender_result = await db.execute(
        select(Identity).where(Identity.user_id == x_user_id)
    )
    if sender_result.scalar_one_or_none() is None:
        raise HTTPException(status_code=403, detail="Unknown sender identity")

    # Rate limiting
    if not await check_rate_limit(db, x_user_id):
        raise HTTPException(status_code=429, detail="Rate limit exceeded")

    # Verify recipient exists
    recipient_check = await db.execute(
        select(Identity).where(Identity.user_id == request.recipient_id)
    )
    if recipient_check.scalar_one_or_none() is None:
        raise HTTPException(status_code=404, detail="Recipient not found")

    # Check pending queue depth
    count_result = await db.execute(
        select(func.count(PendingMessage.id))
        .where(PendingMessage.recipient_id == request.recipient_id)
    )
    pending_count = count_result.scalar_one()
    if pending_count >= MAX_PENDING_MESSAGES_PER_USER:
        raise HTTPException(
            status_code=507,
            detail="Recipient's pending queue is full",
        )

    message_id = uuid.uuid4().hex
    now_utc = datetime.now(timezone.utc)
    timestamp_ms = int(now_utc.timestamp() * 1000)
    now = now_utc.replace(tzinfo=None)

    pending = PendingMessage(
        message_id=message_id,
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        encrypted_body=request.encrypted_body,
        nonce=request.nonce,
        ephemeral_public_key=request.ephemeral_public_key,
        created_at=now,
        timestamp_ms=timestamp_ms,
    )
    db.add(pending)
    await db.commit()

    # Real-time delivery attempt via WebSocket
    ws_delivered = await ws_manager.notify_new_message(
        recipient_id=request.recipient_id,
        sender_id=x_user_id,
    )

    # FCM push if recipient is not connected via WebSocket
    if not ws_delivered:
        result = await db.execute(
            select(Identity).where(Identity.user_id == request.recipient_id)
        )
        recipient = result.scalar_one_or_none()
        if recipient and recipient.fcm_token:
            await send_push_notification(
                fcm_token=recipient.fcm_token,
                sender_id=x_user_id,
                conversation_id=x_user_id,  # Simplified; real impl would have conversation IDs
            )

    return SendMessageResponse(message_id=message_id, timestamp=timestamp_ms)


@router.get("/pending", response_model=List[PendingMessageResponse])
async def fetch_pending_messages(
    x_user_id: str = Header(..., alias="X-User-Id"),
    db: AsyncSession = Depends(get_db),
) -> List[PendingMessageResponse]:
    """Fetch all pending encrypted envelopes for the authenticated user.

    The client decrypts these locally. The envelopes remain in the database
    until the client acknowledges receipt.
    """
    result = await db.execute(
        select(PendingMessage)
        .where(PendingMessage.recipient_id == x_user_id)
        .order_by(PendingMessage.created_at.asc())
    )
    messages = result.scalars().all()

    return [
        PendingMessageResponse(
            message_id=msg.message_id,
            sender_id=msg.sender_id,
            encrypted_body=msg.encrypted_body,
            nonce=msg.nonce,
            ephemeral_public_key=msg.ephemeral_public_key,
            timestamp=msg.timestamp_ms or 0,
        )
        for msg in messages
    ]


@router.post("/ack")
async def acknowledge_messages(
    request: AckRequest,
    x_user_id: str = Header(..., alias="X-User-Id"),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Acknowledge receipt of messages, allowing the server to delete them.

    This is the key privacy mechanism: the server does not retain envelopes
    after the recipient has fetched them.
    """
    if not request.message_ids:
        return {"status": "ok"}

    await db.execute(
        delete(PendingMessage).where(
            PendingMessage.recipient_id == x_user_id,
            PendingMessage.message_id.in_(request.message_ids),
        )
    )
    await db.commit()
    return {"status": "ok"}
