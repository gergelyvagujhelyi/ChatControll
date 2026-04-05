# ChatControll Relay Server

Privacy-first encrypted message relay built with Python and FastAPI.

## What the server does

- Stores public key bundles for contact discovery via share codes
- Relays encrypted envelopes between clients
- Sends FCM wake-up signals for offline delivery (messages and call offers)
- Provides WebSocket connections for real-time delivery
- Buffers undelivered call signals for offline recipients (30s TTL)
- Deletes envelopes after recipient acknowledgement
- Rate-limits senders to prevent abuse

## What the server does NOT do

- Decrypt or inspect message content
- Store message logs after delivery
- Require personally identifiable information
- Upload or process contact books

## Setup

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# Edit .env as needed
```

## Run

```bash
python run.py
# or
uvicorn app.main:app --reload --port 8000
```

API docs available at http://localhost:8000/docs (debug mode only).

## Run tests

```bash
pip install -r requirements.txt
pytest -v
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/v1/identity/bootstrap` | Register a guest identity |
| GET | `/v1/identity/{user_id}/keys` | Fetch public key bundle |
| GET | `/v1/identity/resolve/{share_code}` | Resolve share code to keys |
| PUT | `/v1/identity/me/keys` | Rotate identity keys (proof-of-possession required) |
| DELETE | `/v1/identity/me` | Delete identity and all associated data |
| POST | `/v1/messages/send` | Submit encrypted envelope |
| GET | `/v1/messages/pending` | Fetch pending envelopes |
| POST | `/v1/messages/ack` | Acknowledge receipt |
| POST | `/v1/push/register` | Register FCM token |
| DELETE | `/v1/push/register` | Unregister FCM token |
| POST | `/v1/calls/signal` | Relay call signal to peer |
| GET | `/v1/calls/ice-servers` | Get ICE/TURN server config |
| WS | `/v1/ws` | Real-time delivery + call signaling |
| GET | `/health` | Health check |
| GET | `/metrics` | Prometheus metrics (token-protected) |

All authenticated endpoints require `Authorization: Bearer <user_id>.<timestamp_ms>.<ed25519_signature>` header.

## Production Deployment

```bash
# Configure .env with production values (see .env.example)
# Then:
docker compose up -d --build
```

This starts PostgreSQL + the app server + nginx with TLS. Alembic migrations run automatically on container start.

See `.env.example` for all configuration options including `TURN_SECRET`, `METRICS_TOKEN`, and TLS certificate paths.

### Automated Deployment

A cron-based pull deploy script (`deploy.sh`) is included for lightweight servers. It polls for new commits on `develop`, only rebuilds Docker when files in `server/` actually change, and skips otherwise.

**Setup on the server:**

```bash
crontab -e
# Add (checks every 5 minutes):
*/5 * * * * /path/to/ChatControll/server/deploy.sh >> /var/log/chatcontroll-deploy.log 2>&1
```

Features: file-based locking (no overlapping deploys), Docker layer caching, corrupt/force-pushed commit recovery, and dirty working tree cleanup.

## Database

Default: SQLite (for development). Switch to PostgreSQL by changing
`DATABASE_URL` in `.env`:

```
DATABASE_URL=postgresql+asyncpg://user:password@localhost/chatcontroll
```

Tables are auto-created in debug mode. In production, use Alembic migrations:

```bash
alembic upgrade head
```
