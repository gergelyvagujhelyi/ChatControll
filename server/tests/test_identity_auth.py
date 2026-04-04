"""Tests for authenticated identity endpoints: deletion and key rotation."""

import base64

import pytest
from httpx import AsyncClient

from tests.auth_helpers import generate_ed25519_keypair, make_auth_header


async def _bootstrap_user(client: AsyncClient, label: str) -> dict:
    private_key, pub_key_b64 = generate_ed25519_keypair()
    resp = await client.post(
        "/v1/identity/bootstrap",
        json={
            "public_signing_key": pub_key_b64,
            "public_identity_key": base64.b64encode(f"id_{label}_padding!".encode()).decode(),
            "pqc_encapsulation_key": "",
        },
    )
    data = resp.json()
    data["private_key"] = private_key
    data["pub_key_b64"] = pub_key_b64
    return data


def _auth(user: dict) -> dict:
    return make_auth_header(user["private_key"], user["user_id"])


@pytest.mark.asyncio
async def test_delete_identity(client: AsyncClient):
    """DELETE /v1/identity/me should remove the user and all their data."""
    user = await _bootstrap_user(client, "delete_me")

    resp = await client.delete("/v1/identity/me", headers=_auth(user))
    assert resp.status_code == 200
    assert resp.json()["status"] == "deleted"

    # Verify user is gone
    resp = await client.get(f"/v1/identity/{user['user_id']}/keys")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_delete_clears_pending_messages(client: AsyncClient):
    """Deleting a user should remove their sent and received messages."""
    alice = await _bootstrap_user(client, "alice_del")
    bob = await _bootstrap_user(client, "bob_del")

    # Alice sends a message to Bob
    await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": bob["user_id"],
            "encrypted_body": "hello",
            "nonce": "nonce",
        },
        headers=_auth(alice),
    )

    # Delete Alice — her sent messages should be removed
    await client.delete("/v1/identity/me", headers=_auth(alice))

    # Bob's pending queue should be empty
    resp = await client.get("/v1/messages/pending", headers=_auth(bob))
    assert resp.json() == []


@pytest.mark.asyncio
async def test_delete_requires_auth(client: AsyncClient):
    """DELETE /v1/identity/me without auth should return 422."""
    resp = await client.delete("/v1/identity/me")
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_rotate_keys(client: AsyncClient):
    """PUT /v1/identity/me/keys should update signing and identity keys."""
    user = await _bootstrap_user(client, "rotate_me")

    new_private_key, new_pub_key_b64 = generate_ed25519_keypair()
    new_id_key = base64.b64encode(b"new_identity_key_pad!").decode()

    # Rotate — authenticated with the OLD key
    resp = await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_key_b64,
            "public_identity_key": new_id_key,
        },
        headers=_auth(user),
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"

    # Verify old key no longer works
    resp = await client.get("/v1/messages/pending", headers=_auth(user))
    assert resp.status_code == 401

    # Verify new key works
    new_headers = make_auth_header(new_private_key, user["user_id"])
    resp = await client.get("/v1/messages/pending", headers=new_headers)
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_rotate_keys_updates_key_bundle(client: AsyncClient):
    """After rotation, fetching the key bundle should return the new keys."""
    user = await _bootstrap_user(client, "rotate_bundle")

    new_private_key, new_pub_key_b64 = generate_ed25519_keypair()
    new_id_key = base64.b64encode(b"rotated_id_key_pad!!").decode()

    await client.put(
        "/v1/identity/me/keys",
        json={
            "public_signing_key": new_pub_key_b64,
            "public_identity_key": new_id_key,
        },
        headers=_auth(user),
    )

    resp = await client.get(f"/v1/identity/{user['user_id']}/keys")
    assert resp.status_code == 200
    bundle = resp.json()
    assert bundle["public_signing_key"] == new_pub_key_b64
    assert bundle["public_identity_key"] == new_id_key
