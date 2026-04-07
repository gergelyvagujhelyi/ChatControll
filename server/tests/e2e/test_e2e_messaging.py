"""E2E messaging tests: bootstrap → discover → send → fetch → ack.

Simulates the full protocol two clients perform, using real Ed25519
and ML-DSA-65 signatures against the actual server endpoints.
"""

import base64

import pytest
from httpx import AsyncClient

from tests.e2e.conftest import User, bootstrap_user


# ── Contact discovery ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_add_contact_via_share_code(client: AsyncClient, alice: User, bob: User):
    """Alice resolves Bob's share code and gets his full key bundle."""
    resp = await client.get(f"/v1/identity/resolve/{bob.share_code}")
    assert resp.status_code == 200
    bundle = resp.json()
    assert bundle["user_id"] == bob.user_id
    assert bundle["public_signing_key"] == bob.ed25519_pub_b64
    assert bundle["public_identity_key"] == bob.identity_key_b64


@pytest.mark.asyncio
async def test_add_pqc_contact_returns_pqc_keys(client: AsyncClient, alice: User, bob_pqc: User):
    """Resolving a PQC-enabled contact returns their ML-DSA and ML-KEM keys."""
    resp = await client.get(f"/v1/identity/resolve/{bob_pqc.share_code}")
    bundle = resp.json()
    assert bundle["pqc_signing_key"] == bob_pqc.mldsa_public_b64
    assert bundle["pqc_encapsulation_key"] == bob_pqc.pqc_ek_b64


@pytest.mark.asyncio
async def test_resolve_unknown_share_code_returns_404(client: AsyncClient):
    resp = await client.get("/v1/identity/resolve/NONEXISTENT")
    assert resp.status_code == 404


# ── Message exchange ─────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_full_message_flow(client: AsyncClient, alice: User, bob: User):
    """Alice sends an encrypted message to Bob. Bob fetches and ACKs it."""
    # Alice sends
    send_resp = await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": bob.user_id,
            "encrypted_body": base64.b64encode(b"encrypted_hello").decode(),
            "nonce": base64.b64encode(b"nonce_12bytes").decode(),
            "signature": base64.b64encode(b"ed25519_sig_placeholder").decode(),
        },
        headers=alice.auth_header(),
    )
    assert send_resp.status_code == 200
    msg_id = send_resp.json()["message_id"]
    ts = send_resp.json()["timestamp"]
    assert ts > 0

    # Bob fetches
    fetch_resp = await client.get("/v1/messages/pending", headers=bob.auth_header())
    assert fetch_resp.status_code == 200
    messages = fetch_resp.json()
    assert len(messages) == 1
    msg = messages[0]
    assert msg["message_id"] == msg_id
    assert msg["sender_id"] == alice.user_id
    assert msg["encrypted_body"] == base64.b64encode(b"encrypted_hello").decode()
    assert msg["signature"] == base64.b64encode(b"ed25519_sig_placeholder").decode()

    # Bob ACKs
    ack_resp = await client.post(
        "/v1/messages/ack",
        json={"message_ids": [msg_id]},
        headers=bob.auth_header(),
    )
    assert ack_resp.status_code == 200

    # Queue is now empty
    fetch2 = await client.get("/v1/messages/pending", headers=bob.auth_header())
    assert fetch2.json() == []


@pytest.mark.asyncio
async def test_pqc_message_with_signature(client: AsyncClient, alice_pqc: User, bob_pqc: User):
    """PQC users include pqc_signature field — server stores and returns it."""
    pqc_sig = base64.b64encode(b"mldsa65_signature_placeholder").decode()

    send_resp = await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": bob_pqc.user_id,
            "encrypted_body": base64.b64encode(b"pqc_encrypted").decode(),
            "nonce": base64.b64encode(b"pqc_nonce_12b").decode(),
            "signature": base64.b64encode(b"ed_sig").decode(),
            "pqc_signature": pqc_sig,
        },
        headers=alice_pqc.auth_header(),
    )
    assert send_resp.status_code == 200

    fetch_resp = await client.get("/v1/messages/pending", headers=bob_pqc.auth_header())
    messages = fetch_resp.json()
    assert len(messages) == 1
    assert messages[0]["pqc_signature"] == pqc_sig


@pytest.mark.asyncio
async def test_bidirectional_messaging(client: AsyncClient, alice: User, bob: User):
    """Both Alice and Bob can send messages to each other."""
    # Alice → Bob
    await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": bob.user_id,
            "encrypted_body": "from_alice",
            "nonce": "nonce_a",
        },
        headers=alice.auth_header(),
    )

    # Bob → Alice
    await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": alice.user_id,
            "encrypted_body": "from_bob",
            "nonce": "nonce_b",
        },
        headers=bob.auth_header(),
    )

    # Both receive their message
    alice_msgs = (await client.get("/v1/messages/pending", headers=alice.auth_header())).json()
    bob_msgs = (await client.get("/v1/messages/pending", headers=bob.auth_header())).json()

    assert len(alice_msgs) == 1
    assert alice_msgs[0]["sender_id"] == bob.user_id
    assert alice_msgs[0]["encrypted_body"] == "from_bob"

    assert len(bob_msgs) == 1
    assert bob_msgs[0]["sender_id"] == alice.user_id
    assert bob_msgs[0]["encrypted_body"] == "from_alice"


@pytest.mark.asyncio
async def test_message_to_nonexistent_user_fails(client: AsyncClient, alice: User):
    resp = await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": "does_not_exist",
            "encrypted_body": "hello",
            "nonce": "nonce",
        },
        headers=alice.auth_header(),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_message_ordering_preserved(client: AsyncClient, alice: User, bob: User):
    """Messages are returned in chronological order."""
    for i in range(5):
        await client.post(
            "/v1/messages/send",
            json={
                "recipient_id": bob.user_id,
                "encrypted_body": f"msg_{i}",
                "nonce": f"nonce_{i}",
            },
            headers=alice.auth_header(),
        )

    msgs = (await client.get("/v1/messages/pending", headers=bob.auth_header())).json()
    assert len(msgs) == 5
    for i, msg in enumerate(msgs):
        assert msg["encrypted_body"] == f"msg_{i}"


@pytest.mark.asyncio
async def test_partial_ack(client: AsyncClient, alice: User, bob: User):
    """ACKing some messages leaves the rest in the queue."""
    ids = []
    for i in range(3):
        resp = await client.post(
            "/v1/messages/send",
            json={
                "recipient_id": bob.user_id,
                "encrypted_body": f"msg_{i}",
                "nonce": f"n_{i}",
            },
            headers=alice.auth_header(),
        )
        ids.append(resp.json()["message_id"])

    # ACK only the first message
    await client.post(
        "/v1/messages/ack",
        json={"message_ids": [ids[0]]},
        headers=bob.auth_header(),
    )

    remaining = (await client.get("/v1/messages/pending", headers=bob.auth_header())).json()
    assert len(remaining) == 2
    assert remaining[0]["message_id"] == ids[1]
    assert remaining[1]["message_id"] == ids[2]
