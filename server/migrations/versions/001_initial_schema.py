"""Initial schema — identities, pending_messages, rate_limits.

Revision ID: 001
Revises: None
Create Date: 2026-04-04
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "identities",
        sa.Column("user_id", sa.String(32), primary_key=True),
        sa.Column("public_signing_key", sa.Text, nullable=False),
        sa.Column("public_identity_key", sa.Text, nullable=False),
        sa.Column("pqc_encapsulation_key", sa.Text, nullable=False, server_default=""),
        sa.Column("share_code", sa.String(24), unique=True, nullable=False),
        sa.Column("fcm_token", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("last_seen_at", sa.DateTime, server_default=sa.func.now()),
    )
    op.create_index("ix_identities_share_code", "identities", ["share_code"], unique=True)

    op.create_table(
        "pending_messages",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("message_id", sa.String(64), unique=True, nullable=False),
        sa.Column("sender_id", sa.String(32), nullable=False),
        sa.Column("recipient_id", sa.String(32), nullable=False),
        sa.Column("encrypted_body", sa.Text, nullable=False),
        sa.Column("nonce", sa.Text, nullable=False),
        sa.Column("ephemeral_public_key", sa.Text, nullable=False, server_default=""),
        sa.Column("timestamp_ms", sa.Integer, nullable=True),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
    )
    op.create_index("ix_pending_message_id", "pending_messages", ["message_id"], unique=True)
    op.create_index("ix_pending_recipient", "pending_messages", ["recipient_id", "created_at"])

    op.create_table(
        "rate_limits",
        sa.Column("user_id", sa.String(32), primary_key=True),
        sa.Column("message_count", sa.Integer, server_default="0"),
        sa.Column("window_start", sa.DateTime, server_default=sa.func.now()),
    )


def downgrade() -> None:
    op.drop_table("rate_limits")
    op.drop_table("pending_messages")
    op.drop_table("identities")
