"""SQLAlchemy ORM models for the relay server.

The server stores only what it must to route encrypted envelopes:
- Identity: userId, public keys, share code, push token
- PendingMessage: encrypted envelopes awaiting delivery
"""

from datetime import datetime

from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    Index,
    Integer,
    String,
    Text,
    text,
)
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


class Identity(Base):
    """A registered guest identity. No PII — only cryptographic material."""

    __tablename__ = "identities"

    user_id = Column(String(32), primary_key=True)
    public_signing_key = Column(Text, nullable=False)
    public_identity_key = Column(Text, nullable=False)
    pqc_encapsulation_key = Column(Text, nullable=False, default="")
    share_code = Column(String(24), unique=True, nullable=False, index=True)
    fcm_token = Column(Text, nullable=True)
    created_at = Column(DateTime, server_default=text("(strftime('%Y-%m-%d %H:%M:%f', 'now'))"))
    last_seen_at = Column(DateTime, server_default=text("(strftime('%Y-%m-%d %H:%M:%f', 'now'))"))



class PendingMessage(Base):
    """An encrypted envelope waiting for the recipient to fetch it."""

    __tablename__ = "pending_messages"
    __table_args__ = (Index("ix_pending_recipient", "recipient_id", "created_at"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    message_id = Column(String(64), unique=True, nullable=False, index=True)
    sender_id = Column(String(32), nullable=False)
    recipient_id = Column(String(32), nullable=False)
    encrypted_body = Column(Text, nullable=False)
    nonce = Column(Text, nullable=False)
    ephemeral_public_key = Column(Text, nullable=False, default="")
    timestamp_ms = Column(Integer, nullable=True)
    created_at = Column(DateTime, server_default=text("(strftime('%Y-%m-%d %H:%M:%f', 'now'))"))


class RateLimit(Base):
    """Simple per-user rate tracking."""

    __tablename__ = "rate_limits"

    user_id = Column(String(32), primary_key=True)
    message_count = Column(Integer, default=0)
    window_start = Column(DateTime, server_default=text("(strftime('%Y-%m-%d %H:%M:%f', 'now'))"))
