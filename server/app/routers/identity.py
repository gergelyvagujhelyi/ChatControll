"""Identity endpoints: bootstrap, key bundles, share code resolution.

The server stores public keys and a random user ID. It never sees
private keys or any personally identifiable information.
"""

import base64
import hashlib
import secrets

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import Identity, PendingMessage, RateLimit
from app.services.ip_rate_limiter import check_ip_rate_limit
from app.models.schemas import (
    BootstrapRequest,
    BootstrapResponse,
    KeyBundleResponse,
    KeyRotationRequest,
    ResolveShareCodeResponse,
)

router = APIRouter(prefix="/v1/identity", tags=["identity"])


@router.post("/bootstrap", response_model=BootstrapResponse, dependencies=[Depends(check_ip_rate_limit)])
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
    try:
        db.add(identity)
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(status_code=409, detail="Identity collision — retry")

    return BootstrapResponse(user_id=user_id, share_code=share_code)


@router.get("/{user_id}/keys", response_model=KeyBundleResponse, dependencies=[Depends(check_ip_rate_limit)])
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


@router.get("/resolve/{share_code}", response_model=ResolveShareCodeResponse, dependencies=[Depends(check_ip_rate_limit)])
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


@router.delete("/me")
async def delete_identity(
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Delete the authenticated user's identity and all associated data.

    Removes the identity, all pending messages (sent and received),
    and rate-limit records. This is irreversible.
    """
    await db.execute(
        delete(PendingMessage).where(
            (PendingMessage.sender_id == x_user_id)
            | (PendingMessage.recipient_id == x_user_id)
        )
    )
    await db.execute(delete(RateLimit).where(RateLimit.user_id == x_user_id))
    result = await db.execute(delete(Identity).where(Identity.user_id == x_user_id))
    if result.rowcount == 0:
        raise HTTPException(status_code=404, detail="User not found")
    await db.commit()
    return {"status": "deleted"}


@router.put("/me/keys")
async def rotate_keys(
    request: KeyRotationRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Rotate the authenticated user's public keys.

    The request is authenticated with the *current* signing key.
    The caller must also prove possession of the *new* signing key by
    signing the new public_signing_key with the corresponding new private key.
    After this call, subsequent auth tokens must be signed with the new key.
    """
    # Verify proof-of-possession for the new signing key
    try:
        new_pub_bytes = base64.b64decode(request.public_signing_key, validate=True)
        proof_sig = base64.b64decode(request.new_key_proof, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 in key rotation request")

    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    from cryptography.exceptions import InvalidSignature
    try:
        try:
            new_pub_key = Ed25519PublicKey.from_public_bytes(new_pub_bytes)
        except Exception:
            # Fallback: Bouncy Castle exports SPKI/DER format (44 bytes)
            new_pub_key = load_der_public_key(new_pub_bytes)
            if not isinstance(new_pub_key, Ed25519PublicKey):
                raise ValueError("Not an Ed25519 key")
        new_pub_key.verify(proof_sig, request.public_signing_key.encode("utf-8"))
    except (InvalidSignature, Exception):
        raise HTTPException(status_code=400, detail="New key proof-of-possession failed")

    result = await db.execute(
        select(Identity).where(Identity.user_id == x_user_id)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="User not found")

    identity.public_signing_key = request.public_signing_key
    identity.public_identity_key = request.public_identity_key
    if request.pqc_encapsulation_key is not None:
        identity.pqc_encapsulation_key = request.pqc_encapsulation_key

    # Recompute share code from new identity key
    identity.share_code = _derive_share_code(request.public_identity_key)

    await db.commit()
    return {"status": "ok", "share_code": identity.share_code}


def _derive_share_code(public_identity_key_b64: str) -> str:
    """Derive a short, URL-safe share code from the public identity key.

    Uses 12 bytes (96 bits) of SHA-256 — intentionally short for usability.
    Share codes are public lookup handles (displayed in UI, shared via QR),
    NOT secrets or authentication factors. 96 bits gives negligible collision
    probability at realistic user populations (~2^48 for 50% birthday bound).
    """
    try:
        key_bytes = base64.b64decode(public_identity_key_b64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 public_identity_key")
    digest = hashlib.sha256(key_bytes).digest()
    return base64.urlsafe_b64encode(digest[:12]).decode().rstrip("=")
