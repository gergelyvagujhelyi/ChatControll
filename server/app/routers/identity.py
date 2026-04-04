"""Identity endpoints: bootstrap, key bundles, share code resolution.

The server stores public keys and a random user ID. It never sees
private keys or any personally identifiable information.
"""

import base64
import hashlib
import secrets

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.db import Identity
from app.models.schemas import (
    BootstrapRequest,
    BootstrapResponse,
    KeyBundleResponse,
    ResolveShareCodeResponse,
)

router = APIRouter(prefix="/v1/identity", tags=["identity"])


@router.post("/bootstrap", response_model=BootstrapResponse)
async def bootstrap_identity(
    request: BootstrapRequest,
    db: AsyncSession = Depends(get_db),
) -> BootstrapResponse:
    """Register a new guest identity.

    Generates a random user_id and a share code derived from the public
    identity key. Stores public keys for contact discovery.
    """
    user_id = secrets.token_hex(8)  # 16 chars
    share_code = _derive_share_code(request.public_identity_key)

    # Check share code collision (astronomically unlikely)
    existing = await db.execute(
        select(Identity).where(Identity.share_code == share_code)
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(status_code=409, detail="Identity collision — retry")

    identity = Identity(
        user_id=user_id,
        public_signing_key=request.public_signing_key,
        public_identity_key=request.public_identity_key,
        pqc_encapsulation_key=request.pqc_encapsulation_key,
        share_code=share_code,
        fcm_token=request.fcm_token,
    )
    db.add(identity)
    await db.commit()

    return BootstrapResponse(user_id=user_id, share_code=share_code)


@router.get("/{user_id}/keys", response_model=KeyBundleResponse)
async def fetch_key_bundle(
    user_id: str,
    db: AsyncSession = Depends(get_db),
) -> KeyBundleResponse:
    """Fetch a user's public key bundle for session establishment."""
    result = await db.execute(
        select(Identity).where(Identity.user_id == user_id)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="User not found")

    return KeyBundleResponse(
        user_id=identity.user_id,
        public_signing_key=identity.public_signing_key,
        public_identity_key=identity.public_identity_key,
        pqc_encapsulation_key=identity.pqc_encapsulation_key,
    )


@router.get("/resolve/{share_code}", response_model=ResolveShareCodeResponse)
async def resolve_share_code(
    share_code: str,
    db: AsyncSession = Depends(get_db),
) -> ResolveShareCodeResponse:
    """Resolve a share code to a user's public key bundle.

    This is how contact discovery works: User A gives their share code
    to User B (via QR code, text, etc.), and User B resolves it here.
    """
    result = await db.execute(
        select(Identity).where(Identity.share_code == share_code)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="Share code not found")

    return ResolveShareCodeResponse(
        user_id=identity.user_id,
        public_signing_key=identity.public_signing_key,
        public_identity_key=identity.public_identity_key,
        pqc_encapsulation_key=identity.pqc_encapsulation_key,
    )


def _derive_share_code(public_identity_key_b64: str) -> str:
    """Derive a short, URL-safe share code from the public identity key."""
    try:
        key_bytes = base64.b64decode(public_identity_key_b64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 public_identity_key")
    digest = hashlib.sha256(key_bytes).digest()
    return base64.urlsafe_b64encode(digest[:12]).decode().rstrip("=")
