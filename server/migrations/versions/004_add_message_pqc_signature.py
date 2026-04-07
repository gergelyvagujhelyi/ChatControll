"""Add pqc_signature column to pending_messages for ML-DSA-65 support.

Revision ID: 004
Revises: 003
Create Date: 2026-04-06
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "004"
down_revision: Union[str, None] = "003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "pending_messages",
        sa.Column("pqc_signature", sa.Text, nullable=False, server_default=""),
    )


def downgrade() -> None:
    op.drop_column("pending_messages", "pqc_signature")
