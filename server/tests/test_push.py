"""Tests for push token registration endpoints."""

import base64

import pytest
from httpx import AsyncClient

from tests.auth_helpers import generate_ed25519_keypair, make_auth_header


async def _bootstrap_user(client: AsyncClient, label: str) -> dict:
    """Helper to create a test user with real Ed25519 keys."""
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
    return data


def _auth(user: dict) -> dict:
    return make_auth_header(user["private_key"], user["user_id"])


@pytest.mark.asyncio
async def test_register_push_token(client: AsyncClient):
    """POST /v1/push/register should store the FCM token."""
    user = await _bootstrap_user(client, "push_user")

    resp = await client.post(
        "/v1/push/register",
        json={"token": "fcm_token_abc123", "platform": "android"},
        headers=_auth(user),
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


@pytest.mark.asyncio
async def test_unregister_push_token(client: AsyncClient):
    """DELETE /v1/push/register should remove the FCM token."""
    user = await _bootstrap_user(client, "push_unreg")

    # Register
    await client.post(
        "/v1/push/register",
        json={"token": "fcm_token_xyz"},
        headers=_auth(user),
    )

    # Unregister
    resp = await client.delete(
        "/v1/push/register",
        headers=_auth(user),
    )
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_register_unknown_user(client: AsyncClient):
    """Auth rejects unknown users before the endpoint runs."""
    private_key, _ = generate_ed25519_keypair()
    headers = make_auth_header(private_key, "nonexistent_user")

    resp = await client.post(
        "/v1/push/register",
        json={"token": "token"},
        headers=headers,
    )
    assert resp.status_code == 401
