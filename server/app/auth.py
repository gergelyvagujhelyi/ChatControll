"""Ed25519 signed-token authentication.

Token format: ``<user_id>.<timestamp_ms>.<signature_b64>``

The client signs ``<user_id>.<timestamp_ms>`` (UTF-8) with its Ed25519 private
signing key.  The server verifies the signature against the
``public_signing_key`` stored at bootstrap, and rejects tokens older than
``TOKEN_MAX_AGE_MS`` to prevent replay.

This proves the caller holds the private key for the claimed identity --
a self-asserted X-User-Id header is no longer trusted.
"""

import base64
import logging
import time
from typing import Optional

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.exceptions import InvalidSignature
from fastapi import Depends, Header, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.db import Identity

logger = logging.getLogger(__name__)

# Tokens older than 5 minutes are rejected.
TOKEN_MAX_AGE_MS = 5 * 60 * 1000


def verify_token(token: str, public_key_b64: str) -> str:
    """Verify an auth token and return the user_id.

    Raises ``ValueError`` on any verification failure.
    """
    parts = token.split(".", 2)
    if len(parts) != 3:
        raise ValueError("Malformed token")

    user_id, timestamp_str, sig_b64 = parts

    # --- timestamp freshness ---
    try:
        timestamp_ms = int(timestamp_str)
    except ValueError:
        raise ValueError("Invalid timestamp")

    now_ms = int(time.time() * 1000)
    if abs(now_ms - timestamp_ms) > TOKEN_MAX_AGE_MS:
        raise ValueError("Token expired or clock skew too large")

    # --- signature verification ---
    pub_bytes = base64.b64decode(public_key_b64)
    try:
        # Raw 32-byte Ed25519 public key (Android KeyManager export format)
        public_key = Ed25519PublicKey.from_public_bytes(pub_bytes)
    except Exception:
        # Fallback: the stored key may be in SPKI/DER format (Java default export)
        try:
            from cryptography.hazmat.primitives.serialization import (
                load_der_public_key,
            )
            public_key = load_der_public_key(pub_bytes)
            if not isinstance(public_key, Ed25519PublicKey):
                raise ValueError("Not an Ed25519 key")
        except Exception:
            raise ValueError("Cannot load public signing key")

    signed_payload = f"{user_id}.{timestamp_str}".encode("utf-8")
    signature = base64.b64decode(sig_b64)

    try:
        public_key.verify(signature, signed_payload)
    except InvalidSignature:
        raise ValueError("Invalid signature")

    return user_id


async def verify_auth_token(
    authorization: str = Header(..., alias="Authorization"),
    db: AsyncSession = Depends(get_db),
) -> str:
    """FastAPI dependency that extracts and verifies a signed auth token.

    Returns the verified ``user_id``.

    Expected header: ``Authorization: Bearer <user_id>.<ts>.<sig>``
    """
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing Bearer token")

    token = authorization[7:]  # strip "Bearer "

    # Extract user_id from the token *before* DB lookup
    parts = token.split(".", 2)
    if len(parts) != 3:
        raise HTTPException(status_code=401, detail="Malformed auth token")

    claimed_user_id = parts[0]

    # Fetch the public signing key for this user
    result = await db.execute(
        select(Identity.public_signing_key).where(
            Identity.user_id == claimed_user_id
        )
    )
    public_key_b64 = result.scalar_one_or_none()
    if public_key_b64 is None:
        raise HTTPException(status_code=401, detail="Unknown identity")

    try:
        verified_user_id = verify_token(token, public_key_b64)
    except ValueError as e:
        logger.debug("Auth token rejected for %s: %s", claimed_user_id[:8], e)
        raise HTTPException(status_code=401, detail=str(e))

    return verified_user_id
