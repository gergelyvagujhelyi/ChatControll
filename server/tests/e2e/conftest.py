"""E2E test fixtures — simulated clients with real Ed25519 + ML-DSA-65 keys."""

import base64
import time
from dataclasses import dataclass, field
from typing import Optional

import pytest_asyncio
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from httpx import AsyncClient


@dataclass
class User:
    """A simulated client with full key material."""

    user_id: str
    share_code: str
    ed25519_private: Ed25519PrivateKey
    ed25519_pub_b64: str
    identity_key_b64: str
    # PQC keys (optional — only for hybrid users)
    mldsa_public_b64: str = ""
    mldsa_private: bytes = field(default_factory=bytes, repr=False)
    pqc_ek_b64: str = ""

    def auth_header(self) -> dict:
        """Build Authorization header with dual-signed token."""
        ts = str(int(time.time() * 1000))
        payload = f"{self.user_id}.{ts}"
        payload_bytes = payload.encode("utf-8")

        sig = self.ed25519_private.sign(payload_bytes)
        sig_b64 = base64.b64encode(sig).decode()
        token = f"{payload}.{sig_b64}"

        if self.mldsa_private:
            from pqcrypto.sign.ml_dsa_65 import sign
            pqc_sig = sign(self.mldsa_private, payload_bytes)
            pqc_sig_b64 = base64.b64encode(pqc_sig).decode()
            token += f".{pqc_sig_b64}"

        return {"Authorization": f"Bearer {token}"}


async def bootstrap_user(
    client: AsyncClient,
    *,
    pqc: bool = False,
    label: str = "",
) -> User:
    """Bootstrap a user with real Ed25519 keys and optionally ML-DSA-65 + ML-KEM."""
    ed_private = Ed25519PrivateKey.generate()
    ed_pub_bytes = ed_private.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )
    ed_pub_b64 = base64.b64encode(ed_pub_bytes).decode()
    id_key = base64.b64encode(f"id_{label or 'user'}_{time.monotonic_ns()}".encode()).decode()

    mldsa_pub_b64 = ""
    mldsa_private = b""
    pqc_ek_b64 = ""

    if pqc:
        from pqcrypto.sign.ml_dsa_65 import generate_keypair as dsa_keygen
        mldsa_pub, mldsa_priv = dsa_keygen()
        mldsa_pub_b64 = base64.b64encode(mldsa_pub).decode()
        mldsa_private = mldsa_priv
        # Use a dummy encapsulation key — the server just stores/returns it
        pqc_ek_b64 = base64.b64encode(b"mlkem768_placeholder_ek").decode()

    resp = await client.post(
        "/v1/identity/bootstrap",
        json={
            "public_signing_key": ed_pub_b64,
            "public_identity_key": id_key,
            "pqc_encapsulation_key": pqc_ek_b64,
            "pqc_signing_key": mldsa_pub_b64,
        },
    )
    assert resp.status_code == 200, f"Bootstrap failed: {resp.text}"
    data = resp.json()

    return User(
        user_id=data["user_id"],
        share_code=data["share_code"],
        ed25519_private=ed_private,
        ed25519_pub_b64=ed_pub_b64,
        identity_key_b64=id_key,
        mldsa_public_b64=mldsa_pub_b64,
        mldsa_private=mldsa_private,
        pqc_ek_b64=pqc_ek_b64,
    )


@pytest_asyncio.fixture
async def alice(client: AsyncClient) -> User:
    return await bootstrap_user(client, label="alice")


@pytest_asyncio.fixture
async def bob(client: AsyncClient) -> User:
    return await bootstrap_user(client, label="bob")


@pytest_asyncio.fixture
async def alice_pqc(client: AsyncClient) -> User:
    return await bootstrap_user(client, pqc=True, label="alice_pqc")


@pytest_asyncio.fixture
async def bob_pqc(client: AsyncClient) -> User:
    return await bootstrap_user(client, pqc=True, label="bob_pqc")
