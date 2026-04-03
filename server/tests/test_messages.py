"""Tests for message send, fetch, and acknowledge endpoints."""

import base64

import pytest
from httpx import AsyncClient


async def _bootstrap_user(client: AsyncClient, label: str) -> dict:
    """Helper to create a test user."""
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
        headers={"X-User-Id": alice["user_id"]},
    )
    assert send_resp.status_code == 200
    msg_data = send_resp.json()
    assert "message_id" in msg_data
    assert "timestamp" in msg_data

    # Bob fetches pending messages
    fetch_resp = await client.get(
        "/v1/messages/pending",
        headers={"X-User-Id": bob["user_id"]},
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
        headers={"X-User-Id": alice["user_id"]},
    )
    message_id = send_resp.json()["message_id"]

    # ACK
    ack_resp = await client.post(
        "/v1/messages/ack",
        json={"message_ids": [message_id]},
        headers={"X-User-Id": bob["user_id"]},
    )
    assert ack_resp.status_code == 200

    # Fetch again — should be empty
    fetch_resp = await client.get(
        "/v1/messages/pending",
        headers={"X-User-Id": bob["user_id"]},
    )
    assert fetch_resp.json() == []


@pytest.mark.asyncio
async def test_fetch_pending_empty(client: AsyncClient):
    """Fetching with no pending messages returns empty list."""
    user = await _bootstrap_user(client, "lonely")
    resp = await client.get(
        "/v1/messages/pending",
        headers={"X-User-Id": user["user_id"]},
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
            headers={"X-User-Id": alice["user_id"]},
        )

    resp = await client.get(
        "/v1/messages/pending",
        headers={"X-User-Id": bob["user_id"]},
    )
    messages = resp.json()
    assert len(messages) == 3
    assert messages[0]["encrypted_body"] == "msg_0"
    assert messages[2]["encrypted_body"] == "msg_2"
