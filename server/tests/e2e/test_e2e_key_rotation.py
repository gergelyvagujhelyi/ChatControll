"""E2E key rotation tests: rotate → re-auth → PQC downgrade prevention.

Tests the full key lifecycle including proof-of-possession, PQC key
rotation with ML-DSA proofs, and the security invariant that PQC keys
cannot be cleared once set.
"""

import base64

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from httpx import AsyncClient

from tests.e2e.conftest import User, bootstrap_user


# ── Helpers ──────────────────────────────────────────────────────────


def _new_ed25519():
    pk = Ed25519PrivateKey.generate()
    pub = pk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return pk, base64.b64encode(pub).decode()


def _ed25519_proof(new_private: Ed25519PrivateKey, new_pub_b64: str) -> str:
    sig = new_private.sign(new_pub_b64.encode("utf-8"))
    return base64.b64encode(sig).decode()


# ── Classical key rotation ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_rotate_keys_then_reauth(client: AsyncClient, alice: User):
    """After rotation, old key is rejected and new key works."""
    new_pk, new_pub_b64 = _new_ed25519()
    new_id_key = base64.b64encode(b"rotated_identity_key!").decode()
    proof = _ed25519_proof(new_pk, new_pub_b64)

    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": new_id_key,
            "new_key_proof": proof,
        },
        headers=alice.auth_header(),
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
    new_share_code = resp.json()["share_code"]

    # Old key rejected
    old_resp = await client.get("/v1/messages/pending", headers=alice.auth_header())
    assert old_resp.status_code == 401

    # New key works — build header manually
    alice.ed25519_private = new_pk
    alice.ed25519_pub_b64 = new_pub_b64
    new_resp = await client.get("/v1/messages/pending", headers=alice.auth_header())
    assert new_resp.status_code == 200


@pytest.mark.asyncio
async def test_rotate_updates_key_bundle(client: AsyncClient, alice: User):
    """After rotation, the key bundle endpoint returns the new keys."""
    new_pk, new_pub_b64 = _new_ed25519()
    new_id_key = base64.b64encode(b"new_bundle_id_key!!!").decode()
    proof = _ed25519_proof(new_pk, new_pub_b64)

    await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": new_id_key,
            "new_key_proof": proof,
        },
        headers=alice.auth_header(),
    )

    bundle = (await client.get(f"/v1/identity/{alice.user_id}/keys")).json()
    assert bundle["public_signing_key"] == new_pub_b64
    assert bundle["public_identity_key"] == new_id_key


@pytest.mark.asyncio
async def test_rotate_updates_share_code(client: AsyncClient, alice: User):
    """Share code changes when identity key changes."""
    old_share_code = alice.share_code

    new_pk, new_pub_b64 = _new_ed25519()
    new_id_key = base64.b64encode(b"different_id_key!!!!").decode()
    proof = _ed25519_proof(new_pk, new_pub_b64)

    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": new_id_key,
            "new_key_proof": proof,
        },
        headers=alice.auth_header(),
    )
    new_share_code = resp.json()["share_code"]
    assert new_share_code != old_share_code

    # Old share code no longer resolves
    old_resolve = await client.get(f"/v1/identity/resolve/{old_share_code}")
    assert old_resolve.status_code == 404

    # New share code works
    new_resolve = await client.get(f"/v1/identity/resolve/{new_share_code}")
    assert new_resolve.status_code == 200
    assert new_resolve.json()["user_id"] == alice.user_id


@pytest.mark.asyncio
async def test_rotate_bad_proof_rejected(client: AsyncClient, alice: User):
    """Rotation with wrong proof-of-possession is rejected."""
    new_pk, new_pub_b64 = _new_ed25519()
    wrong_pk, _ = _new_ed25519()
    bad_proof = _ed25519_proof(wrong_pk, new_pub_b64)

    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": alice.identity_key_b64,
            "new_key_proof": bad_proof,
        },
        headers=alice.auth_header(),
    )
    assert resp.status_code == 400


# ── PQC key rotation ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_rotate_with_pqc_keys(client: AsyncClient, alice_pqc: User):
    """Rotate both Ed25519 and ML-DSA keys with valid proofs."""
    from pqcrypto.sign.ml_dsa_65 import generate_keypair as dsa_keygen, sign as dsa_sign

    new_pk, new_pub_b64 = _new_ed25519()
    new_id_key = base64.b64encode(b"pqc_rotated_id_key!!").decode()
    ed_proof = _ed25519_proof(new_pk, new_pub_b64)

    new_dsa_pub, new_dsa_priv = dsa_keygen()
    new_dsa_pub_b64 = base64.b64encode(new_dsa_pub).decode()
    pqc_proof = base64.b64encode(
        dsa_sign(new_dsa_priv, new_dsa_pub_b64.encode("utf-8"))
    ).decode()

    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": new_id_key,
            "new_key_proof": ed_proof,
            "pqc_signing_key": new_dsa_pub_b64,
            "pqc_key_proof": pqc_proof,
        },
        headers=alice_pqc.auth_header(),
    )
    assert resp.status_code == 200

    # Verify new PQC key is stored
    bundle = (await client.get(f"/v1/identity/{alice_pqc.user_id}/keys")).json()
    assert bundle["pqc_signing_key"] == new_dsa_pub_b64

    # Auth with new dual-signed token works
    alice_pqc.ed25519_private = new_pk
    alice_pqc.mldsa_private = new_dsa_priv
    resp = await client.get("/v1/messages/pending", headers=alice_pqc.auth_header())
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_cannot_clear_pqc_key_once_set(client: AsyncClient, alice_pqc: User):
    """Security: PQC signing key cannot be removed — prevents auth downgrade."""
    new_pk, new_pub_b64 = _new_ed25519()
    ed_proof = _ed25519_proof(new_pk, new_pub_b64)

    # Try to clear PQC key by sending empty string
    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": alice_pqc.identity_key_b64,
            "new_key_proof": ed_proof,
            "pqc_signing_key": "",
        },
        headers=alice_pqc.auth_header(),
    )
    assert resp.status_code == 400
    assert "Cannot clear PQC" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_cannot_clear_pqc_key_with_null(client: AsyncClient, alice_pqc: User):
    """Security: sending null pqc_signing_key also blocked."""
    new_pk, new_pub_b64 = _new_ed25519()
    ed_proof = _ed25519_proof(new_pk, new_pub_b64)

    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": alice_pqc.identity_key_b64,
            "new_key_proof": ed_proof,
            "pqc_signing_key": None,
        },
        headers=alice_pqc.auth_header(),
    )
    assert resp.status_code == 400
    assert "Cannot clear PQC" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_pqc_rotation_without_proof_rejected(client: AsyncClient, alice_pqc: User):
    """PQC key rotation without proof-of-possession is rejected."""
    from pqcrypto.sign.ml_dsa_65 import generate_keypair as dsa_keygen

    new_pk, new_pub_b64 = _new_ed25519()
    ed_proof = _ed25519_proof(new_pk, new_pub_b64)
    new_dsa_pub, _ = dsa_keygen()
    new_dsa_pub_b64 = base64.b64encode(new_dsa_pub).decode()

    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_b64,
            "public_identity_key": alice_pqc.identity_key_b64,
            "new_key_proof": ed_proof,
            "pqc_signing_key": new_dsa_pub_b64,
            # Missing pqc_key_proof
        },
        headers=alice_pqc.auth_header(),
    )
    assert resp.status_code == 400


# ── Account deletion ─────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_delete_identity_clears_everything(client: AsyncClient):
    """Deleting an account removes identity, messages, and share code."""
    alice = await bootstrap_user(client, label="del_alice")
    bob = await bootstrap_user(client, label="del_bob")

    # Alice sends a message
    await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": bob.user_id,
            "encrypted_body": "goodbye",
            "nonce": "n",
        },
        headers=alice.auth_header(),
    )

    # Delete Alice
    resp = await client.delete("/v1/identity/me", headers=alice.auth_header())
    assert resp.status_code == 200

    # Alice's key bundle is gone
    assert (await client.get(f"/v1/identity/{alice.user_id}/keys")).status_code == 404

    # Alice's share code no longer resolves
    assert (await client.get(f"/v1/identity/resolve/{alice.share_code}")).status_code == 404

    # Bob's pending queue is empty (Alice's message was purged)
    msgs = (await client.get("/v1/messages/pending", headers=bob.auth_header())).json()
    assert msgs == []
