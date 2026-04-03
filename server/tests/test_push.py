"""Tests for push token registration endpoints."""

import base64

import pytest
from httpx import AsyncClient


async def _bootstrap_user(client: AsyncClient, label: str) -> dict:
    resp = await client.post(
        "/v1/identity/bootstrap",
        json={
            "public_signing_key": base64.b64encode(f"sign_{label}".encode()).decode(),
            "public_identity_key": base64.b64encode(f"id_{label}_padding!".encode()).decode(),
            "pqc_encapsulation_key": "",
        },
    )
    return resp.json()


@pytest.mark.asyncio
async def test_register_push_token(client: AsyncClient):
    """POST /v1/push/register should store the FCM token."""
    user = await _bootstrap_user(client, "push_user")

    resp = await client.post(
        "/v1/push/register",
        json={"token": "fcm_token_abc123", "platform": "android"},
        headers={"X-User-Id": user["user_id"]},
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
        headers={"X-User-Id": user["user_id"]},
    )

    # Unregister
    resp = await client.delete(
        "/v1/push/register",
        headers={"X-User-Id": user["user_id"]},
    )
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_register_unknown_user(client: AsyncClient):
    """POST /v1/push/register with unknown user should return 404."""
    resp = await client.post(
        "/v1/push/register",
        json={"token": "token"},
        headers={"X-User-Id": "nonexistent_user"},
    )
    assert resp.status_code == 404
