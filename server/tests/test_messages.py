"""Tests for message send, fetch, and acknowledge endpoints."""

import base64

import pytest
from httpx import AsyncClient

from tests.auth_helpers import generate_ed25519_keypair, make_auth_header


async def _bootstrap_user(client: AsyncClient, label: str) -> dict:
    """Helper to create a test user with real Ed25519 keys.

    Returns dict with 'user_id', 'private_key', and auth helper.
    """
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
    """Shorthand to build auth headers for a bootstrapped user."""
    return make_auth_header(user["private_key"], user["user_id"])


@pytest.mark.asyncio
async def test_send_and_fetch_message(client: AsyncClient):
    """Full flow: Alice sends to Bob, Bob fetches the encrypted envelope."""
    alice = await _bootstrap_user(client, "alice")
    bob = await _bootstrap_user(client, "bob")

    # Alice sends a message
    send_resp = await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": bob["user_id"],
            "encrypted_body": base64.b64encode(b"encrypted_hello").decode(),
            "nonce": base64.b64encode(b"nonce_12byte").decode(),
        },
        headers=_auth(alice),
    )
    assert send_resp.status_code == 200
    msg_data = send_resp.json()
    assert "message_id" in msg_data
    assert "timestamp" in msg_data

    # Bob fetches pending messages
    fetch_resp = await client.get(
        "/v1/messages/pending",
        headers=_auth(bob),
    )
    assert fetch_resp.status_code == 200
    messages = fetch_resp.json()
    assert len(messages) == 1
    assert messages[0]["sender_id"] == alice["user_id"]
    assert messages[0]["encrypted_body"] == base64.b64encode(b"encrypted_hello").decode()
    assert messages[0]["message_id"] == msg_data["message_id"]


@pytest.mark.asyncio
async def test_ack_deletes_messages(client: AsyncClient):
    """After ACK, messages should be deleted from pending."""
    alice = await _bootstrap_user(client, "alice_ack")
    bob = await _bootstrap_user(client, "bob_ack")

    # Send
    send_resp = await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": bob["user_id"],
            "encrypted_body": "encrypted",
            "nonce": "nonce",
        },
        headers=_auth(alice),
    )
    message_id = send_resp.json()["message_id"]

    # ACK
    ack_resp = await client.post(
        "/v1/messages/ack",
        json={"message_ids": [message_id]},
        headers=_auth(bob),
    )
    assert ack_resp.status_code == 200

    # Fetch again — should be empty
    fetch_resp = await client.get(
        "/v1/messages/pending",
        headers=_auth(bob),
    )
    assert fetch_resp.json() == []


@pytest.mark.asyncio
async def test_fetch_pending_empty(client: AsyncClient):
    """Fetching with no pending messages returns empty list."""
    user = await _bootstrap_user(client, "lonely")
    resp = await client.get(
        "/v1/messages/pending",
        headers=_auth(user),
    )
    assert resp.status_code == 200
    assert resp.json() == []


@pytest.mark.asyncio
async def test_multiple_messages_ordered(client: AsyncClient):
    """Multiple messages should be returned in chronological order."""
    alice = await _bootstrap_user(client, "alice_multi")
    bob = await _bootstrap_user(client, "bob_multi")

    for i in range(3):
        await client.post(
            "/v1/messages/send",
            json={
                "recipient_id": bob["user_id"],
                "encrypted_body": f"msg_{i}",
                "nonce": f"nonce_{i}",
            },
            headers=_auth(alice),
        )

    resp = await client.get(
        "/v1/messages/pending",
        headers=_auth(bob),
    )
    messages = resp.json()
    assert len(messages) == 3
    assert messages[0]["encrypted_body"] == "msg_0"
    assert messages[2]["encrypted_body"] == "msg_2"
