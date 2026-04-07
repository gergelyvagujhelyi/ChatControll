"""Add pqc_signing_key column to identities for ML-DSA-65 support.

Revision ID: 003
Revises: 002
Create Date: 2026-04-06
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "003"
down_revision: Union[str, None] = "002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "identities",
        sa.Column("pqc_signing_key", sa.Text, nullable=False, server_default=""),
    )


def downgrade() -> None:
    op.drop_column("identities", "pqc_signing_key")
