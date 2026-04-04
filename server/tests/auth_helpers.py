"""Ed25519 authentication helpers for tests."""

import base64
import time

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def generate_ed25519_keypair() -> tuple:
    """Generate an Ed25519 keypair. Returns (private_key, public_key_b64)."""
    private_key = Ed25519PrivateKey.generate()
    public_bytes = private_key.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )
    return private_key, base64.b64encode(public_bytes).decode()


def make_auth_header(private_key: Ed25519PrivateKey, user_id: str) -> dict:
    """Create an Authorization header with a signed Bearer token."""
    ts = str(int(time.time() * 1000))
    payload = f"{user_id}.{ts}"
    signature = private_key.sign(payload.encode("utf-8"))
    sig_b64 = base64.b64encode(signature).decode()
    return {"Authorization": f"Bearer {payload}.{sig_b64}"}
