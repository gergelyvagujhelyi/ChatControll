"""Pydantic request/response schemas matching the Android client DTOs."""

from typing import List, Optional

from pydantic import BaseModel


class BootstrapRequest(BaseModel):
    public_signing_key: str
    public_identity_key: str
    pqc_encapsulation_key: str = ""
    fcm_token: Optional[str] = None


class BootstrapResponse(BaseModel):
    user_id: str
    share_code: str


class KeyBundleResponse(BaseModel):
    user_id: str
    public_signing_key: str
    public_identity_key: str
    pqc_encapsulation_key: str


class ResolveShareCodeResponse(BaseModel):
    user_id: str
    public_signing_key: str
    public_identity_key: str
    pqc_encapsulation_key: str


class SendMessageRequest(BaseModel):
    recipient_id: str
    encrypted_body: str
    nonce: str
    ephemeral_public_key: str = ""


class SendMessageResponse(BaseModel):
    message_id: str
    timestamp: int


class PendingMessageResponse(BaseModel):
    message_id: str
    sender_id: str
    encrypted_body: str
    nonce: str
    ephemeral_public_key: str = ""
    timestamp: int


class AckRequest(BaseModel):
    message_ids: List[str]


class PushTokenRequest(BaseModel):
    token: str
    platform: str = "android"


class ErrorResponse(BaseModel):
    code: str
    message: str


class HealthResponse(BaseModel):
    status: str = "ok"
    version: str = "0.1.0"
