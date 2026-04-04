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
MAX_REQUEST_BODY_BYTES: int = int(os.getenv("MAX_REQUEST_BODY_BYTES", str(2 * 1024 * 1024)))  # 2 MB
CORS_ORIGINS: list = [o.strip() for o in os.getenv("CORS_ORIGINS", "").split(",") if o.strip()]
WS_IDLE_TIMEOUT_SECONDS: int = int(os.getenv("WS_IDLE_TIMEOUT_SECONDS", "300"))  # 5 min
METRICS_TOKEN: str = os.getenv("METRICS_TOKEN", "")
TURN_ENABLED: bool = os.getenv("TURN_ENABLED", "true").lower() == "true"
# For Android emulators, use 10.0.2.2 (host alias inside emulator).
# For real devices, set to your server's public IP.
TURN_RELAY_IP: str = os.getenv("TURN_RELAY_IP", "10.0.2.2")
TURN_SECRET: str = os.getenv("TURN_SECRET", "")
TURN_CREDENTIAL_TTL: int = int(os.getenv("TURN_CREDENTIAL_TTL", "3600"))
# Legacy static credentials (deprecated — use TURN_SECRET for ephemeral creds)
# Comma-separated list of trusted proxy IPs that may set X-Forwarded-For
TRUSTED_PROXIES: set = {
    ip.strip()
    for ip in os.getenv("TRUSTED_PROXIES", "127.0.0.1,::1").split(",")
    if ip.strip()
}
