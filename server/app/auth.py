"""Ed25519 + ML-DSA-65 signed-token authentication.

Token format: ``<user_id>.<timestamp_ms>.<ed25519_sig_b64>[.<mldsa_sig_b64>]``

The client signs ``<user_id>.<timestamp_ms>`` (UTF-8) with its Ed25519 private
signing key, and optionally with its ML-DSA-65 private key.  The server
verifies Ed25519 against the stored ``public_signing_key``, and ML-DSA-65
against the stored ``pqc_signing_key`` (if both are present).

This provides hybrid post-quantum authentication: an attacker must break
both Ed25519 AND ML-DSA-65 to forge an auth token.
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

# ML-DSA-65 raw public key is 1952 bytes.
_MLDSA65_RAW_PK_LEN = 1952
# Expected SPKI/DER header for ML-DSA-65 (22 bytes):
#   SEQUENCE { SEQUENCE { OID 2.16.840.1.101.3.4.3.18 } BIT STRING ... }
_MLDSA65_SPKI_HEADER = bytes.fromhex(
    "308207b2"          # SEQUENCE (outer)
    "300b"              # SEQUENCE (algorithm identifier)
    "0609608648016503040312"  # OID 2.16.840.1.101.3.4.3.18 (ML-DSA-65)
    "038207a100"        # BIT STRING header + unused-bits byte
)


def _extract_mldsa_raw_pk(key_bytes: bytes) -> Optional[bytes]:
    """Extract raw ML-DSA-65 public key from X509/SPKI DER or raw encoding."""
    if len(key_bytes) == _MLDSA65_RAW_PK_LEN:
        return key_bytes  # Already raw (e.g. from pqcrypto library)
    if len(key_bytes) == len(_MLDSA65_SPKI_HEADER) + _MLDSA65_RAW_PK_LEN:
        if not key_bytes.startswith(_MLDSA65_SPKI_HEADER):
            return None  # Not a valid ML-DSA-65 SPKI encoding
        return key_bytes[len(_MLDSA65_SPKI_HEADER):]  # Strip DER header
    return None


def _verify_mldsa_signature(
    raw_pk: bytes, message: bytes, signature: bytes,
) -> bool:
    """Verify an ML-DSA-65 signature using pqcrypto."""
    from pqcrypto.sign.ml_dsa_65 import verify
    try:
        verify(raw_pk, message, signature)
        return True
    except (ValueError, TypeError):
        return False
    except Exception:
        logger.exception("Unexpected error during ML-DSA-65 verification")
        raise


def verify_token(
    token: str,
    public_key_b64: str,
    pqc_signing_key_b64: str = "",
) -> str:
    """Verify an auth token and return the user_id.

    Raises ``ValueError`` on any verification failure.
    """
    parts = token.split(".")
    if len(parts) < 3 or len(parts) > 4:
        raise ValueError("Malformed token")

    user_id = parts[0]
    timestamp_str = parts[1]
    sig_b64 = parts[2]
    pqc_sig_b64 = parts[3] if len(parts) == 4 else ""

    # --- timestamp freshness ---
    try:
        timestamp_ms = int(timestamp_str)
    except ValueError:
        raise ValueError("Invalid timestamp")

    now_ms = int(time.time() * 1000)
    if abs(now_ms - timestamp_ms) > TOKEN_MAX_AGE_MS:
        raise ValueError("Token expired or clock skew too large")

    # --- Ed25519 signature verification ---
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
        raise ValueError("Invalid Ed25519 signature")

    # --- ML-DSA-65 signature verification ---
    # If the user has a PQC signing key, the ML-DSA signature is REQUIRED.
    # Allowing it to be omitted would let an attacker who breaks Ed25519
    # bypass the hybrid auth model entirely.
    if pqc_signing_key_b64:
        if not pqc_sig_b64:
            raise ValueError("ML-DSA-65 signature required")
        pqc_key_bytes = base64.b64decode(pqc_signing_key_b64)
        raw_pk = _extract_mldsa_raw_pk(pqc_key_bytes)
        if raw_pk is None:
            raise ValueError("Invalid ML-DSA-65 public key format")
        pqc_sig = base64.b64decode(pqc_sig_b64)
        if not _verify_mldsa_signature(raw_pk, signed_payload, pqc_sig):
            raise ValueError("Invalid ML-DSA-65 signature")

    return user_id


async def verify_auth_token(
    authorization: str = Header(..., alias="Authorization"),
    db: AsyncSession = Depends(get_db),
) -> str:
    """FastAPI dependency that extracts and verifies a signed auth token.

    Returns the verified ``user_id``.

    Expected header: ``Authorization: Bearer <user_id>.<ts>.<ed25519_sig>[.<mldsa_sig>]``
    """
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Authentication failed")

    token = authorization[7:]  # strip "Bearer "

    # Extract user_id from the token *before* DB lookup
    parts = token.split(".")
    if len(parts) < 3 or len(parts) > 4:
        raise HTTPException(status_code=401, detail="Authentication failed")

    claimed_user_id = parts[0]

    # Fetch the public signing key (and PQC signing key) for this user
    result = await db.execute(
        select(Identity.public_signing_key, Identity.pqc_signing_key).where(
            Identity.user_id == claimed_user_id
        )
    )
    row = result.one_or_none()
    if row is None:
        raise HTTPException(status_code=401, detail="Authentication failed")

    public_key_b64, pqc_signing_key_b64 = row

    try:
        verified_user_id = verify_token(
            token, public_key_b64, pqc_signing_key_b64 or "",
        )
    except ValueError as e:
        logger.debug("Auth token rejected for %s: %s", claimed_user_id[:8], e)
        raise HTTPException(status_code=401, detail="Authentication failed")

    return verified_user_id
