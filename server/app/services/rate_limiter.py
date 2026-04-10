"""Simple per-user rate limiter backed by the database.

Tracks message send count per rolling 1-minute window.
This is a privacy-preserving abuse control: it limits throughput without
inspecting message content or building behavioral profiles.
"""

from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import MAX_MESSAGES_PER_MINUTE
from app.models.db import RateLimit


async def check_rate_limit(db: AsyncSession, user_id: str) -> bool:
    """Return True if the user is within rate limits, False if exceeded."""
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    window_start = now - timedelta(minutes=1)

    result = await db.execute(
        select(RateLimit).where(RateLimit.user_id == user_id).with_for_update()
    )
    rate = result.scalar_one_or_none()

    if rate is None:
        try:
            async with db.begin_nested():
                db.add(RateLimit(user_id=user_id, message_count=1, window_start=now))
                await db.flush()
        except IntegrityError:
            # Another request inserted the row concurrently; re-read with lock
            result = await db.execute(
                select(RateLimit).where(RateLimit.user_id == user_id).with_for_update()
            )
            rate = result.scalar_one_or_none()
            if rate is None:
                return True  # Shouldn't happen, but allow the request
            # Check window expiration (same logic as the main path)
            if rate.window_start is None or rate.window_start < window_start:
                rate.message_count = 1
                rate.window_start = now
                await db.flush()
                return True
            if rate.message_count >= MAX_MESSAGES_PER_MINUTE:
                return False
            rate.message_count += 1
            await db.flush()
        return True

    # Reset window if expired
    if rate.window_start is None or rate.window_start < window_start:
        rate.message_count = 1
        rate.window_start = now
        await db.flush()
        return True

    if rate.message_count >= MAX_MESSAGES_PER_MINUTE:
        return False

    rate.message_count += 1
    await db.flush()
    return True
