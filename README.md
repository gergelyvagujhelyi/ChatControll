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
- **Push**: Firebase Cloud Messaging (wake-up signal only — no message content in push payloads)
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
│   ├── MockPqcProvider.kt          # Fallback mock for testing
│   ├── HybridCryptoEngine.kt       # Combines classical + PQC (static sessions)
│   ├── KeyManager.kt               # Keystore-backed key storage
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
├── ui/
│   ├── theme/                  # Material 3 theme
│   ├── components/             # Reusable Compose components
│   ├── onboarding/             # Guest identity creation flow
│   ├── conversations/          # Conversation list
│   ├── chat/                   # Chat thread + composer
│   ├── contacts/               # Add contact via share code
│   ├── settings/               # Privacy & security settings
│   └── navigation/             # Nav graph
└── worker/                     # WorkManager tasks (sync, retry)
```

## Key Design Decisions

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full architecture decision record.

## Security

See [SECURITY.md](SECURITY.md) for the threat model, security notes, and known limitations.

## Implemented Features

1. **Production ML-KEM-768** — `BouncyCastlePqcProvider` uses Bouncy Castle 1.79+ for real NIST FIPS 203 post-quantum key encapsulation. The hybrid X25519 + ML-KEM key establishment is fully functional.

2. **Double Ratchet protocol** — `RatchetSessionManager` implements Signal-style per-message forward secrecy with DH ratchet steps and symmetric chain ratchets. Compromising current state does not reveal past messages.

3. **Python relay server** — Full FastAPI backend in `server/` with SQLite/PostgreSQL support, WebSocket real-time delivery, FCM push forwarding, rate limiting, and comprehensive test suite. `KtorApiService` + `WebSocketClient` connect the Android app to it.

4. **Encrypted voice calls** — WebRTC-based 1:1 voice calls with end-to-end encrypted signaling. Ephemeral HMAC-based TURN credentials (coturn-compatible) are generated per session.

5. **Ed25519 authentication** — All API requests are authenticated with Ed25519 signed tokens. Message envelopes are signed by the sender and verified by the recipient.

6. **Security hardening (v0.3.0–0.3.1)** — Protected metrics endpoint, generic auth errors, WebSocket call signal validation and rate limiting, debug logging gated behind `BuildConfig.DEBUG`, certificate pinning configuration, call signal Ed25519 signatures, per-challenge TURN nonce rotation (RFC 5389), ICE candidate bounds checking.

## Next Priorities

1. **Ratchet state persistence** — Serialize Double Ratchet session state to the encrypted database so sessions survive app restarts without re-keying.

2. **Multi-device support** �� Allow users to link multiple devices under one identity, with device-specific ratchet sessions and synchronized message delivery.

3. **Key rotation protocol** — Periodic identity key rotation for compromise recovery and device migration.

## License

See [LICENSE](LICENSE).
