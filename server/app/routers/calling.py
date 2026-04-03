"""REST endpoints for voice call signaling and ICE server configuration."""

from fastapi import APIRouter, Header, Request

from app.models.schemas import (
    CallSignalRequest,
    CallSignalResponse,
    IceServer,
    IceServersResponse,
)
from app.services.websocket_manager import ws_manager

router = APIRouter(prefix="/v1/calls", tags=["calling"])


@router.post("/signal", response_model=CallSignalResponse)
async def relay_signal(
    request: CallSignalRequest,
    x_user_id: str = Header(...),
) -> CallSignalResponse:
    delivered = await ws_manager.relay_call_signal(
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        signal_type=request.signal_type,
        call_id=request.call_id,
        encrypted_payload=request.encrypted_payload,
    )
    return CallSignalResponse(delivered=delivered)


@router.get("/ice-servers", response_model=IceServersResponse)
async def get_ice_servers(request: Request) -> IceServersResponse:
    """Return ICE server configuration for WebRTC calls."""
    servers = [
        IceServer(urls="stun:stun.l.google.com:19302"),
    ]

    relay_ip = getattr(request.app.state, "turn_relay_ip", None)
    if relay_ip:
        servers.append(
            IceServer(
                urls=f"turn:{relay_ip}:3478?transport=udp",
                username="test",
                credential="test",
            )
        )

    return IceServersResponse(ice_servers=servers)
