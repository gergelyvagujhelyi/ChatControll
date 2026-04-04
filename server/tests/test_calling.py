"""Tests for call signaling endpoints."""

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
    return data


def _auth(user: dict) -> dict:
    return make_auth_header(user["private_key"], user["user_id"])


@pytest.mark.asyncio
async def test_ice_servers_requires_auth(client: AsyncClient):
    """GET /v1/calls/ice-servers without auth should return 422."""
    resp = await client.get("/v1/calls/ice-servers")
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_ice_servers_with_auth(client: AsyncClient):
    """GET /v1/calls/ice-servers with valid auth returns STUN server."""
    user = await _bootstrap_user(client, "ice_user")
    resp = await client.get("/v1/calls/ice-servers", headers=_auth(user))
    assert resp.status_code == 200
    data = resp.json()
    assert len(data["ice_servers"]) >= 1
    assert "stun:" in data["ice_servers"][0]["urls"]


@pytest.mark.asyncio
async def test_relay_signal_requires_auth(client: AsyncClient):
    """POST /v1/calls/signal without auth should return 422."""
    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": "someone",
            "signal_type": "call_offer",
            "call_id": "abc",
            "encrypted_payload": "data",
        },
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_relay_signal_to_offline_user(client: AsyncClient):
    """Call signal to offline recipient should return delivered=false."""
    alice = await _bootstrap_user(client, "caller")
    bob = await _bootstrap_user(client, "callee")

    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": bob["user_id"],
            "signal_type": "call_offer",
            "call_id": "call123",
            "encrypted_payload": "encrypted_sdp",
        },
        headers=_auth(alice),
    )
    assert resp.status_code == 200
    assert resp.json()["delivered"] is False
