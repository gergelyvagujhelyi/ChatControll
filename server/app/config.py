"""Server configuration loaded from environment variables."""

import os
from typing import Optional

from dotenv import load_dotenv

load_dotenv()

DATABASE_URL: str = os.getenv(
    "DATABASE_URL", "sqlite+aiosqlite:///./chatcontroll.db"
)
FIREBASE_CREDENTIALS: Optional[str] = os.getenv("FIREBASE_CREDENTIALS")
HOST: str = os.getenv("HOST", "0.0.0.0")
PORT: int = int(os.getenv("PORT", "8000"))
DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"
MAX_MESSAGES_PER_MINUTE: int = int(os.getenv("MAX_MESSAGES_PER_MINUTE", "60"))
MAX_PENDING_MESSAGES_PER_USER: int = int(
    os.getenv("MAX_PENDING_MESSAGES_PER_USER", "1000")
)
TURN_ENABLED: bool = os.getenv("TURN_ENABLED", "true").lower() == "true"
# For Android emulators, use 10.0.2.2 (host alias inside emulator).
# For real devices, set to your server's public IP.
TURN_RELAY_IP: str = os.getenv("TURN_RELAY_IP", "10.0.2.2")
