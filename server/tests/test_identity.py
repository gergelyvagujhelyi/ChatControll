"""Tests for identity bootstrap and key discovery endpoints."""

import base64

import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_bootstrap_creates_identity(client: AsyncClient):
    """POST /v1/identity/bootstrap should create a new user."""
    response = await client.post(
        "/v1/identity/bootstrap",
        json={
            "public_signing_key": base64.b64encode(b"sign_key_32bytes_padding_here!!").decode(),
            "public_identity_key": base64.b64encode(b"id_key_32bytes_padding_here!!!!").decode(),
            "pqc_encapsulation_key": base64.b64encode(b"pqc_key_placeholder").decode(),
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert "user_id" in data
    assert "share_code" in data
    assert len(data["user_id"]) == 16


@pytest.mark.asyncio
async def test_fetch_key_bundle(client: AsyncClient):
    """GET /v1/identity/{user_id}/keys should return the public key bundle."""
    # Bootstrap first
    bootstrap = await client.post(
        "/v1/identity/bootstrap",
        json={
            "public_signing_key": base64.b64encode(b"sign_key_test").decode(),
            "public_identity_key": base64.b64encode(b"id_key_test_1234").decode(),
            "pqc_encapsulation_key": "",
        },
    )
    user_id = bootstrap.json()["user_id"]

    # Fetch keys
    response = await client.get(f"/v1/identity/{user_id}/keys")
    assert response.status_code == 200
    data = response.json()
    assert data["user_id"] == user_id
    assert data["public_signing_key"] == base64.b64encode(b"sign_key_test").decode()


@pytest.mark.asyncio
async def test_fetch_key_bundle_not_found(client: AsyncClient):
    """GET /v1/identity/{unknown}/keys should return 404."""
    response = await client.get("/v1/identity/nonexistent/keys")
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_resolve_share_code(client: AsyncClient):
    """GET /v1/identity/resolve/{share_code} should return the key bundle."""
    bootstrap = await client.post(
        "/v1/identity/bootstrap",
        json={
            "public_signing_key": base64.b64encode(b"sign_resolve").decode(),
            "public_identity_key": base64.b64encode(b"id_resolve_test!").decode(),
            "pqc_encapsulation_key": "",
        },
    )
    share_code = bootstrap.json()["share_code"]

    response = await client.get(f"/v1/identity/resolve/{share_code}")
    assert response.status_code == 200
    data = response.json()
    assert data["public_signing_key"] == base64.b64encode(b"sign_resolve").decode()


@pytest.mark.asyncio
async def test_resolve_unknown_share_code(client: AsyncClient):
    """GET /v1/identity/resolve/{unknown} should return 404."""
    response = await client.get("/v1/identity/resolve/UNKNOWN_CODE")
    assert response.status_code == 404
