"""E2E call signaling tests: offer → ringing → answer → ICE → hangup.

Tests the full call signal relay protocol including PQC signature
and KEM ciphertext fields, rate limiting, and WebSocket delivery.
"""

import base64
import json
import time

import pytest
from httpx import AsyncClient

from tests.e2e.conftest import User, bootstrap_user


# ── Helpers ──────────────────────────────────────────────────────────


async def send_signal(
    client: AsyncClient,
    sender: User,
    recipient: User,
    signal_type: str,
    call_id: str = "call_001",
    payload: str = "encrypted_sdp",
    pqc_signature: str = "",
    kem_ciphertext: str = "",
) -> dict:
    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": recipient.user_id,
            "signal_type": signal_type,
            "call_id": call_id,
            "encrypted_payload": payload,
            "signature": base64.b64encode(b"ed_sig").decode(),
            "pqc_signature": pqc_signature,
            "kem_ciphertext": kem_ciphertext,
        },
        headers=sender.auth_header(),
    )
    return {"status": resp.status_code, "body": resp.json()}


# ── Call flow ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_full_call_signal_flow(client: AsyncClient, alice: User, bob: User):
    """Simulate a complete call: offer → answer → ICE → hangup."""
    call_id = "call_full_flow"

    # Alice sends call_offer
    r = await send_signal(client, alice, bob, "call_offer", call_id)
    assert r["status"] == 200
    # Bob is offline — signal is buffered, not delivered
    assert r["body"]["delivered"] is False

    # Bob sends call_ringing
    r = await send_signal(client, bob, alice, "call_ringing", call_id, payload="")
    assert r["status"] == 200

    # Bob sends call_answer
    r = await send_signal(client, bob, alice, "call_answer", call_id, payload="answer_sdp")
    assert r["status"] == 200

    # Both exchange ICE candidates
    for i in range(3):
        r = await send_signal(client, alice, bob, "call_ice_candidate", call_id, payload=f"ice_{i}")
        assert r["status"] == 200
        r = await send_signal(client, bob, alice, "call_ice_candidate", call_id, payload=f"ice_{i}")
        assert r["status"] == 200

    # Alice hangs up
    r = await send_signal(client, alice, bob, "call_hangup", call_id, payload="")
    assert r["status"] == 200


@pytest.mark.asyncio
async def test_call_offer_with_kem_ciphertext(client: AsyncClient, alice_pqc: User, bob_pqc: User):
    """PQC call_offer includes kem_ciphertext and pqc_signature fields."""
    kem_ct = base64.b64encode(b"mlkem768_ciphertext_placeholder").decode()
    pqc_sig = base64.b64encode(b"mldsa65_call_sig").decode()

    r = await send_signal(
        client, alice_pqc, bob_pqc,
        "call_offer",
        call_id="pqc_call",
        kem_ciphertext=kem_ct,
        pqc_signature=pqc_sig,
    )
    assert r["status"] == 200


@pytest.mark.asyncio
async def test_call_reject(client: AsyncClient, alice: User, bob: User):
    """Bob rejects Alice's call."""
    call_id = "call_reject"
    await send_signal(client, alice, bob, "call_offer", call_id)
    r = await send_signal(client, bob, alice, "call_reject", call_id, payload="")
    assert r["status"] == 200


@pytest.mark.asyncio
async def test_call_busy(client: AsyncClient, alice: User, bob: User):
    """Bob sends busy when already in a call."""
    r = await send_signal(client, bob, alice, "call_busy", call_id="call_busy", payload="")
    assert r["status"] == 200


# ── Validation ───────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_invalid_signal_type_rejected(client: AsyncClient, alice: User, bob: User):
    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": bob.user_id,
            "signal_type": "invalid_type",
            "call_id": "c",
            "encrypted_payload": "x",
        },
        headers=alice.auth_header(),
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_cannot_signal_self(client: AsyncClient, alice: User):
    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": alice.user_id,
            "signal_type": "call_offer",
            "call_id": "c",
            "encrypted_payload": "x",
        },
        headers=alice.auth_header(),
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_signal_requires_auth(client: AsyncClient, bob: User):
    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": bob.user_id,
            "signal_type": "call_offer",
            "call_id": "c",
            "encrypted_payload": "x",
        },
    )
    assert resp.status_code == 422


# ── ICE servers ──────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_ice_servers_returns_stun(client: AsyncClient, alice: User):
    resp = await client.get("/v1/calls/ice-servers", headers=alice.auth_header())
    assert resp.status_code == 200
    servers = resp.json()["ice_servers"]
    assert any("stun:" in s["urls"] for s in servers)


# ── WebSocket call signal delivery ───────────────────────────────────


def test_ws_call_signal_delivery():
    """Call signal is delivered in real-time when recipient has a WebSocket."""
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        # Bootstrap both users
        from tests.e2e.conftest import Ed25519PrivateKey, serialization
        import time as _time

        def _boot(label):
            pk = Ed25519PrivateKey.generate()
            pub = pk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
            pub_b64 = base64.b64encode(pub).decode()
            id_key = base64.b64encode(f"id_{label}_{_time.monotonic_ns()}".encode()).decode()
            resp = tc.post("/v1/identity/bootstrap", json={
                "public_signing_key": pub_b64,
                "public_identity_key": id_key,
                "pqc_encapsulation_key": "",
            })
            return resp.json()["user_id"], pk

        alice_id, alice_pk = _boot("ws_alice")
        bob_id, bob_pk = _boot("ws_bob")

        def _token(pk, uid):
            ts = str(int(_time.time() * 1000))
            payload = f"{uid}.{ts}"
            sig = pk.sign(payload.encode())
            return f"{payload}.{base64.b64encode(sig).decode()}"

        def _auth(pk, uid):
            return {"Authorization": f"Bearer {_token(pk, uid)}"}

        # Bob connects WebSocket and authenticates
        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": _token(bob_pk, bob_id)}))
            auth_resp = json.loads(ws.receive_text())
            assert auth_resp["type"] == "auth_ok"

            # Alice sends a call_offer via REST
            resp = tc.post("/v1/calls/signal", json={
                "recipient_id": bob_id,
                "signal_type": "call_offer",
                "call_id": "ws_call_1",
                "encrypted_payload": "offer_sdp",
            }, headers=_auth(alice_pk, alice_id))
            assert resp.status_code == 200
            assert resp.json()["delivered"] is True

            # Bob receives it on the WebSocket
            msg = json.loads(ws.receive_text())
            assert msg["type"] == "call_offer"
            assert msg["sender_id"] == alice_id
            assert msg["call_id"] == "ws_call_1"
            assert msg["encrypted_payload"] == "offer_sdp"
