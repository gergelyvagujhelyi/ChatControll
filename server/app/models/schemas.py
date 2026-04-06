"""Pydantic request/response schemas matching the Android client DTOs."""

from typing import Annotated, List, Optional

from pydantic import BaseModel, Field


class BootstrapRequest(BaseModel):
    public_signing_key: str = Field(..., max_length=4096)
    public_identity_key: str = Field(..., max_length=4096)
    pqc_encapsulation_key: str = Field("", max_length=8192)
    pqc_signing_key: str = Field("", max_length=8192)
    fcm_token: Optional[str] = Field(None, max_length=4096)


class BootstrapResponse(BaseModel):
    user_id: str
    share_code: str


class KeyBundleResponse(BaseModel):
    user_id: str
    public_signing_key: str
    public_identity_key: str
    pqc_encapsulation_key: str
    pqc_signing_key: str


class ResolveShareCodeResponse(BaseModel):
    user_id: str
    public_signing_key: str
    public_identity_key: str
    pqc_encapsulation_key: str
    pqc_signing_key: str


class KeyRotationRequest(BaseModel):
    public_signing_key: str = Field(..., max_length=4096)
    public_identity_key: str = Field(..., max_length=4096)
    pqc_encapsulation_key: Optional[str] = Field(None, max_length=8192)
    pqc_signing_key: Optional[str] = Field(None, max_length=8192)
    new_key_proof: str = Field(..., max_length=512, description="Signature of the new public_signing_key by the new private key (proof of possession)")


class SendMessageRequest(BaseModel):
    recipient_id: str = Field(..., max_length=64)
    encrypted_body: str = Field(..., max_length=262_144)  # 256 KB — sufficient for chat messages
    nonce: str = Field(..., max_length=4096)  # Carries ratchet header JSON; first PQC msg includes ML-KEM ciphertext (~1.6 KB)
    ephemeral_public_key: str = Field("", max_length=4096)
    signature: str = Field("", max_length=512)
    pqc_signature: str = Field("", max_length=4608)  # ML-DSA-65 signature


class SendMessageResponse(BaseModel):
    message_id: str
    timestamp: int


class PendingMessageResponse(BaseModel):
    message_id: str
    sender_id: str
    encrypted_body: str
    nonce: str
    ephemeral_public_key: str = ""
    signature: str = ""
    pqc_signature: str = ""
    timestamp: int


class AckRequest(BaseModel):
    message_ids: List[Annotated[str, Field(max_length=64)]] = Field(..., max_length=1000)


class PushTokenRequest(BaseModel):
    token: str = Field(..., max_length=4096)
    platform: str = "android"


class ErrorResponse(BaseModel):
    code: str
    message: str


class HealthResponse(BaseModel):
    status: str = "ok"
    version: str = "0.3.5"


class CallSignalRequest(BaseModel):
    recipient_id: str = Field(..., max_length=64)
    signal_type: str = Field(..., max_length=64)
    call_id: str = Field(..., max_length=128)
    encrypted_payload: str = Field(..., max_length=65536)
    signature: str = Field("", max_length=512)
    pqc_signature: str = Field("", max_length=4608)  # ML-DSA-65 signature (~4412 B base64)
    kem_ciphertext: str = Field("", max_length=2048)  # ML-KEM-768 ciphertext (~1452 B base64); only on call_offer


class CallSignalResponse(BaseModel):
    delivered: bool


class IceServer(BaseModel):
    urls: str
    username: Optional[str] = None
    credential: Optional[str] = None


class IceServersResponse(BaseModel):
    ice_servers: List[IceServer]
