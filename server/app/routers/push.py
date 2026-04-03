"""Push token registration endpoints.

Clients register their FCM push token so the server can send wake-up
signals when new messages arrive and the recipient is offline.
"""

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.db import Identity
from app.models.schemas import PushTokenRequest

router = APIRouter(prefix="/v1/push", tags=["push"])


@router.post("/register")
async def register_push_token(
    request: PushTokenRequest,
    x_user_id: str = Header(..., alias="X-User-Id"),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Register or update the FCM push token for the authenticated user."""
    result = await db.execute(
        select(Identity).where(Identity.user_id == x_user_id)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="User not found")

    identity.fcm_token = request.token
    await db.commit()
    return {"status": "ok"}


@router.delete("/register")
async def unregister_push_token(
    x_user_id: str = Header(..., alias="X-User-Id"),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Remove the FCM push token for the authenticated user."""
    result = await db.execute(
        select(Identity).where(Identity.user_id == x_user_id)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="User not found")

    identity.fcm_token = None
    await db.commit()
    return {"status": "ok"}
