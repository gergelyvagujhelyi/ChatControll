# ChatControll

Privacy-first Android chat application with hybrid post-quantum cryptography.

## Overview

ChatControll is a 1:1 messaging app designed around three principles:

1. **No registration** — Your identity is a cryptographic keypair generated on your device. No email, phone number, or real name required.
2. **End-to-end encryption** — Messages are encrypted before leaving your device using a hybrid classical + post-quantum scheme.
3. **Minimal metadata** — The relay server sees only encrypted envelopes and the minimum routing information needed for delivery.

## Architecture

```
┌─────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose + ViewModels)    │
├─────────────────────────────────────────────┤
│  Domain Layer (Use Cases + Interfaces)      │
├──────────────┬──────────────────────────────┤
│  Data Layer  │  Crypto Layer               │
│  Room+SQLCi  │  X25519 + ML-KEM + AES-GCM │
│  Ktor Client │  Behind CryptoEngine iface  │
├──────────────┴──────────────────────────────┤
│  Infrastructure (Hilt, FCM, WorkManager)    │
└─────────────────────────────────────────────┘
```

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: Clean Architecture + MVVM
- **DI**: Hilt
- **Local storage**: Room + SQLCipher (AES-256 encrypted database)
- **Networking**: Ktor Client (mock service layer included)
- **Push**: Firebase Cloud Messaging (wake-up signal with sender ID for notification routing — no message content in push payloads)
- **Crypto**: Hybrid X25519 + ML-KEM-768 key establishment, AES-256-GCM payloads

## Build & Run

### Prerequisites

- Android Studio Ladybug (2024.2.1) or later
- JDK 17
- Android SDK 35
- A device or emulator running Android 8.0 (API 26) or later

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/your-org/ChatControll.git
   cd ChatControll
   ```

2. Open in Android Studio or build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Firebase setup** (optional for push notifications):
   - Create a Firebase project at https://console.firebase.google.com
   - Download `google-services.json` and place it in `app/`
   - Uncomment the `google-services` plugin line in `app/build.gradle.kts`

4. Install on a connected device:
   ```bash
   ./gradlew installDebug
   ```

### Running the Python relay server

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python run.py
```

The server runs at http://localhost:8000. The Android debug build points at `10.0.2.2:8000` (Android emulator's alias for host localhost).

To use the real server, swap the binding in `di/NetworkModule.kt` from `MockApiService` to `KtorApiService`.

### Running without a server

The app works standalone with the mock API service (default). No backend server needed for development — all state is in-process.

### Running without Firebase

Push notifications will be disabled but all other features function normally.

## Project Structure

```
app/src/main/kotlin/com/chatcontroll/app/
├── ChatControllApp.kt          # Application + WorkManager init
├── MainActivity.kt             # Single activity, screen security
├── crypto/                     # Cryptographic engine
│   ├── ClassicalKeyAgreement.kt    # X25519 + Ed25519 interface
│   ├── AndroidClassicalKeyAgreement.kt  # JCA implementation
│   ├── PqcProvider.kt              # ML-KEM interface
│   ├── BouncyCastlePqcProvider.kt  # Production ML-KEM-768 (BC 1.79+)
│   ├── MockPqcProvider.kt          # Test-only mock (not used in production)
│   ├── HybridCryptoEngine.kt       # Combines classical + PQC (static sessions)
│   ├── KeyManager.kt               # Keystore-backed key storage
│   ├── SessionResetSender.kt       # Sends session_reset / account_deleted control msgs
│   ├── SignatureUtils.kt           # Shared signature payload construction
│   └── ratchet/                # Double Ratchet protocol
│       ├── DoubleRatchet.kt        # Signal-style ratchet implementation
│       ├── RatchetSessionManager.kt # CryptoEngine backed by ratchet
│       ├── RatchetState.kt         # Mutable session state
│       ├── ChainKey.kt             # Symmetric chain key derivation
│       └── RatchetHeader.kt        # Per-message ratchet header
├── data/
│   ├── local/                  # Room database, DAOs, entities
│   ├── remote/                 # API contract, DTOs, mock + Ktor clients
│   └── repository/             # Repository implementations
├── di/                         # Hilt modules
├── domain/
│   ├── model/                  # Domain models
│   ├── repository/             # Repository + CryptoEngine interfaces
│   └── usecase/                # Business logic
├── notification/               # FCM service, notification manager
├── call/                       # Call infrastructure
│   ├── CallManager.kt             # Call state machine + signaling
│   └── WebRtcEngine.kt            # WebRTC peer connection + FrameCryptor
├── ui/
│   ├── theme/                  # Material 3 theme
│   ├── components/             # Reusable Compose components
│   │   ├── CallEventItem.kt       # Inline call history events
│   │   ├── KeyChangeEventItem.kt  # Key rotation / account deletion events
│   │   ├── MessageBubble.kt       # Chat message bubble
│   │   ├── ConversationItem.kt    # Conversation list row
│   │   ├── EmptyState.kt          # Empty placeholder
│   │   └── QrCodeImage.kt         # Share code QR rendering
│   ├── onboarding/             # Guest identity creation flow
│   ├── conversations/          # Conversation list
│   ├── chat/                   # Chat thread + composer + PQC indicator
│   ├── call/                   # Call screen + ViewModel
│   ├── contacts/               # Add contact via share code
│   ├── settings/               # Privacy, security settings & account deletion
│   └── navigation/             # Nav graph
└── worker/                     # WorkManager tasks (sync, retry)
```

## Key Design Decisions

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full architecture decision record.

## Security

See [SECURITY.md](SECURITY.md) for the threat model, security notes, and known limitations.

## Implemented Features

1. **Production ML-KEM-768** — `BouncyCastlePqcProvider` uses Bouncy Castle 1.79+ for real NIST FIPS 203 post-quantum key encapsulation. The hybrid X25519 + ML-KEM key establishment is fully functional.

2. **Double Ratchet protocol** — `RatchetSessionManager` implements Signal-style per-message forward secrecy with DH ratchet steps and symmetric chain ratchets. Session state is persisted to EncryptedSharedPreferences and survives app restarts. Compromising current state does not reveal past messages.

3. **Python relay server** — Full FastAPI backend in `server/` with SQLite/PostgreSQL support, WebSocket real-time delivery, FCM push forwarding, rate limiting, and comprehensive test suite. `KtorApiService` + `WebSocketClient` connect the Android app to it.

4. **Encrypted voice calls** — WebRTC-based 1:1 voice calls with end-to-end encrypted signaling and frame-level media encryption via WebRTC FrameCryptor (AES-GCM + HKDF). Ephemeral HMAC-based TURN credentials (coturn-compatible) are generated per session. Call signals are relayed via WebSocket with FCM push fallback and server-side buffering for offline recipients (v0.3.7). Call events (missed, answered with duration) appear inline in chat history.

5. **Ed25519 authentication** — All API requests are authenticated with Ed25519 signed tokens. Message envelopes are signed by the sender and verified by the recipient.

6. **Security hardening (v0.3.0–0.3.1)** — Protected metrics endpoint, generic auth errors, WebSocket call signal validation and rate limiting, debug logging gated behind `BuildConfig.DEBUG`, certificate pinning configuration, call signal Ed25519 signatures, per-challenge TURN nonce rotation (RFC 5389), ICE candidate bounds checking.

7. **Key rotation (v0.3.3)** — `rotateIdentityKeys()` regenerates Ed25519 + X25519 + ML-KEM-768 keys with crash-safe staged promotion. Server validates proof-of-possession before accepting new keys.

8. **PQC negotiation & downgrade protection (v0.3.4)** — Either party can initiate ML-KEM-768 encapsulation (symmetric negotiation). PQC downgrade from hybrid to classical-only is rejected. Base64 input validation on all externally-received key material. Call signal reliability fix ensures reject/hangup reaches the peer.

9. **Session reset on key rotation (v0.3.5)** — When a peer rotates their identity keys, the recipient detects decryption failure and sends a `session_reset` control message through the existing message pipeline (guaranteed delivery). The sender's UI blocks further messages until they manually re-establish the session with the peer's new keys. Contact key material is updated automatically from the server.

10. **Call signaling reliability (v0.3.7)** — Call signals now fall back to FCM push when the recipient's WebSocket is disconnected. The server buffers undelivered signals (30s TTL) and flushes them when the recipient reconnects. The caller sees "Contact unavailable" after a 35s ringing timeout instead of ringing indefinitely. Hangup/reject UI responds instantly (signal sent fire-and-forget in background).

11. **Call to new contacts (v0.3.8)** — Calls to newly added contacts now work even before any messages have been exchanged. The recipient fetches the caller's key bundle from the server on demand, verifies the call signal signature, and only then persists the contact locally.

12. **Key change events in chat history (v0.3.9)** — Local and remote key rotations are now recorded as in-conversation events. When you rotate your keys, each contact's chat shows "You rotated your keys"; when a peer rotates, their chat shows "Peer rotated their keys". Events are rendered with a distinct key icon and centered layout.

13. **Account deletion flow (v0.3.9)** — Wiping local data now sends an `account_deleted` control message to all contacts and deletes the server-side identity before clearing local storage. Peers see a distinct "Peer deleted their account" event (with error-colored icon) and sending is permanently blocked for that conversation. If the server is unreachable during wipe, a retry/skip dialog lets the user choose to retry or delete locally anyway.

14. **Navigation ghost-click fix (v0.3.9)** — During Compose Navigation exit animations, both the outgoing and incoming composable are in the composition tree. Tapping the same screen position could trigger actions on the outgoing screen (e.g., call button firing when settings button was pressed). Fixed by checking `Lifecycle.State.RESUMED` before processing interactive actions.

15. **Faster navigation transitions (v0.3.9)** — Screen transitions reduced to 150ms slide+fade for snappier navigation feel.

16. **Push notification fix (v0.3.10)** — FCM push payload now includes sender ID for notification routing. Notifications are now shown by `MessageRepositoryImpl` after decryption, displaying the real sender name and decrypted message body instead of generic "New encrypted message" text. Notifications are suppressed when the conversation is already open. On FCM receipt, WebSocket is reconnected to flush buffered call signals.

17. **QA bug fixes (v0.3.9 / server v0.3.4)** — Fixed race conditions in WebSocket client connection management and call signal processing. Eliminated resource leak from `HttpClient` recreation on each WebSocket reconnect (reverted to singleton). Fixed rate limiter bypass where disconnecting reset per-user quota — rate limit state is now preserved across reconnects and pruned periodically. Improved rate limiter pruning performance under load with high-water mark and time-gated scans. Fixed replay cache eviction, `ContactEntity` equality, share code mismatch, and concurrent `_pendingNewContact` access during calls.

18. **ML-DSA-65 dual-signing (v0.4.0)** — All auth tokens, messages, and call signals are now dual-signed with Ed25519 + ML-DSA-65 when PQC keys are available. The server enforces mandatory ML-DSA verification for users with PQC signing keys, preventing downgrade attacks. Key rotation requires proof-of-possession for both Ed25519 and ML-DSA-65.

19. **Hybrid PQC call encryption (v0.4.0)** — Call session keys are derived via a hybrid X25519 + ML-KEM-768 key agreement using SHAKE-256 KDF (SHA-3 family), independent from the messaging Double Ratchet. Call signal payloads are encrypted with AES-256-GCM using per-call ephemeral keys. The `call_ringing` signal provides accurate "Ringing..." status based on peer confirmation.

20. **Security hardening (v0.4.0)** — Fixed 21 security and correctness bugs including: endCallGuard race condition leaking WebRTC resources, late call_answer reviving terminal calls, PQC signing key not persisted on contact upgrade (signature verification gap), SPKI header OID validation, process kill after database wipe, duplicated auth token logic, and unconditional secret zeroization.

21. **QA security audit fixes (v0.4.1)** — Fixed 17 bugs across server and client from a full security audit: WebSocket pre-auth DoS (10s auth timeout), malformed Base64 envelope crashing sync loop, key rotation crash recovery promoting unconfirmed keys, FLAG_SECURE not reactive to setting changes, control message infinite retry without PQC signature, production logcat leak, self-send on message endpoint, Dockerfile running as root, metrics cardinality pollution, missing nginx WebSocket headers, path parameter validation, and `getDatabaseKey()` race condition. Added automatic purge of pending messages older than 30 days.

## Next Priorities

1. **Multi-device support** — Allow users to link multiple devices under one identity, with device-specific ratchet sessions and synchronized message delivery.

2. **Automated key rotation schedule** — Periodic identity key rotation with old-key grace period for in-flight messages.

3. **Push proxy** — Break FCM token linkability by routing wake-up signals through a proxy service.

## License

See [LICENSE](LICENSE).
