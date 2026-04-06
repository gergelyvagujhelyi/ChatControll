# Architecture Decision Record

## ADR-1: Clean Architecture with MVVM

**Decision**: Separate the app into UI, domain, data, and crypto layers with strict dependency rules.

**Why**: The crypto layer and transport layer are the most likely components to change (new PQC library, new backend). Clean boundaries make these swappable without touching UI or business logic. MVVM with StateFlow gives reactive, testable ViewModels that work naturally with Compose.

**Tradeoff**: More boilerplate than a single-module app. Acceptable for a security-critical application where correctness and maintainability matter more than initial velocity.

---

## ADR-2: Hilt over Koin

**Decision**: Use Hilt for dependency injection.

**Why**: Compile-time verification of the DI graph catches configuration errors early. First-class support for Android components (ViewModels, WorkManager, Services). Scoping lifecycle is handled automatically.

**Tradeoff**: Heavier annotation processing than Koin. Worth it for the safety guarantees in a security-sensitive app.

---

## ADR-3: SQLCipher-backed Room database

**Decision**: Encrypt the local database with SQLCipher, keyed from Android Keystore.

**Why**: The database contains encrypted message blobs, conversation metadata, and contact keys. Even though message bodies are encrypted at the application layer, metadata (who talked to whom, when) is sensitive. SQLCipher encrypts the entire database file at rest.

**Tradeoff**: ~2MB APK size increase. Minor performance overhead on queries (negligible for chat workloads).

---

## ADR-4: Hybrid classical + post-quantum key establishment

**Decision**: Combine X25519 ECDH with ML-KEM-768 via HKDF, behind a `CryptoEngine` interface.

**Why**: X25519 is battle-tested and provides strong classical security today. ML-KEM-768 (FIPS 203) provides quantum resistance. The hybrid approach means:
- If the PQC component is broken, classical security remains.
- If classical crypto is broken by a quantum computer, the PQC component provides protection.
- The `CryptoEngine` interface allows swapping implementations without redesigning the app.

**Current state**: `BouncyCastlePqcProvider` provides production ML-KEM-768 via Bouncy Castle 1.79+. The hybrid X25519 + ML-KEM key establishment is fully functional with symmetric negotiation (either party can initiate encapsulation). PQC downgrade from hybrid to classical-only is rejected. `MockPqcProvider` exists for JVM unit tests only.

**Tradeoff**: Larger key bundles and slight handshake overhead. Acceptable for a messaging app where handshakes are infrequent.

---

## ADR-5: Anonymous guest identity

**Decision**: Generate cryptographic identity on first launch with no PII.

**Why**: Traditional registration (email/phone) creates a correlation point. A locally-generated Ed25519 signing key + X25519 identity key serves as the user's identity. Discovery happens via share codes (truncated hash of the public key).

**Tradeoffs**:
- **No account recovery**: If the device is lost, the identity is gone. This is a deliberate privacy choice. Opt-in encrypted backup could be added later.
- **No server-side identity verification**: The server cannot verify that a user "owns" an identity beyond possession of the private key.
- **Spam/abuse**: Without registration friction, spam is easier. Mitigated by share-code-based contact discovery (you must know someone's code to message them).

---

## ADR-6: FCM as wake-up signal only

**Decision**: Use Firebase Cloud Messaging to wake the device, but never send message content through FCM.

**Why**: FCM payloads pass through Google's infrastructure. Even if encrypted, metadata (sender, timing) would be visible. Instead, the FCM notification contains only the sender ID for notification routing — never message content. The app then fetches the encrypted envelope directly from the relay server.

**Tradeoff**: Slightly higher latency (FCM wake-up + fetch) vs. including the payload in the push. Worth the privacy improvement.

**Extension (v0.3.7)**: The same wake-up-only pattern now applies to call signaling. When a call offer cannot be delivered via WebSocket (recipient offline), the server sends an FCM wake-up push and buffers the signal. The recipient's app reconnects WebSocket on push receipt and receives the buffered signal. Call signal content never passes through FCM.

**Extension (v0.3.10)**: On FCM receipt, the client now also calls `webSocketClient.ensureConnected()` to ensure buffered call signals are delivered immediately. Notifications are shown by `MessageRepositoryImpl` after decryption — not by `FcmService` — so they display the real sender name and decrypted message body instead of generic text. Notifications are suppressed for the conversation currently open on screen.

---

## ADR-7: Mock API service layer

**Decision**: Define the full API contract as an interface (`ApiService`) with an in-process mock implementation for development.

**Why**: Allows the full app to run without a backend server. The interface contract is well-defined, making it straightforward to swap in a real Ktor client implementation. The mock maintains in-memory state, simulating the relay server's behavior.

**Tradeoff**: Mock doesn't simulate network latency, failures, or concurrent access from multiple devices. These must be tested with a real backend.

---

## ADR-8: Privacy defaults over convenience

**Decision**: All privacy settings default to the most restrictive option.

- Lock screen previews: hidden
- Read receipts: off
- Screen security: on (FLAG_SECURE)
- Cloud backup: disabled
- Telemetry: none

**Why**: Users who want convenience can opt in. Users who want privacy shouldn't have to remember to opt out.

---

## ADR-9: No contact book upload

**Decision**: Contact discovery is exclusively via share codes and invitation links.

**Why**: Uploading contact books (even hashed) creates a social graph on the server and risks de-anonymization. Share-code-based discovery is more friction but fundamentally more private.

**Tradeoff**: Less convenient onboarding. Users must manually exchange share codes. QR code scanning and invite links reduce this friction.

---

## ADR-10: Ratchet state persistence via EncryptedSharedPreferences

**Decision**: Persist Double Ratchet session state (root key, chain keys, message counters, skipped keys) to EncryptedSharedPreferences rather than the Room database.

**Why**: Ratchet state changes on every message sent or received. Using EncryptedSharedPreferences avoids Room schema migrations for rapidly evolving state and keeps crypto material out of the SQLCipher database, which may be backed up or exported. EncryptedSharedPreferences is backed by Android Keystore, providing hardware-backed encryption at rest.

**Tradeoff**: Not suitable for multi-device sync (SharedPreferences is device-local). Full DB-backed persistence will be needed for multi-device support.

---

## ADR-11: Crash-safe key rotation with staged promotion

**Decision**: Use a two-phase commit for identity key rotation: stage new keys locally before the server call, then promote to active only after server acceptance.

**Why**: If the app crashes after the server accepts new keys but before the client updates its local storage, the client would be out of sync with the server. Staged keys allow automatic recovery on next launch: detect staged keys, promote them, and invalidate session caches.

**Tradeoff**: Slightly more complex key storage (active + staged slots in KeyManager). Justified by the severity of a split-brain key state.

---

## ADR-12: Session reset via control messages in the message pipeline

**Decision**: After key rotation causes decrypt failures, notify the sender via a control message sent through the existing `/v1/messages/send` endpoint rather than introducing a new WebSocket signal type or REST endpoint.

**Why**: WebSocket signals are ephemeral — if the sender is offline, the signal is lost and they keep sending undecryptable messages. The message pipeline is store-and-forward with FCM fallback, guaranteeing delivery. Control messages are distinguished by a JSON nonce (`{"ctrl":"session_reset"}`) with an empty body. No server changes are required — the server relays the control message like any other opaque envelope.

**Why manual re-establishment**: The sender must tap "Re-establish session" rather than auto-re-keying. This gives the sender explicit visibility that the peer rotated keys, which is important for security awareness (similar to Signal's "safety number changed" notification). Auto-re-keying would silently mask key changes.

**Tradeoff**: The sender sees a blocked conversation until they act. This is the intended UX — security visibility over convenience.

---

## ADR-13: Account deletion via control message with `peerDeleted` flag

**Decision**: Distinguish account deletion from key rotation by introducing an `account_deleted` control message type and a `peerDeleted` boolean on the conversation entity. When a user wipes their data, each contact receives `{"ctrl":"account_deleted"}` instead of `{"ctrl":"session_reset"}`.

**Why**: Key rotation and account deletion have very different implications for the peer. After key rotation, the peer can re-establish the session and continue chatting. After account deletion, the peer identity is gone — further messages would be undeliverable. Using a distinct control message lets the recipient's UI show "Peer deleted their account" with permanent send-blocking, rather than offering a "Re-establish session" action that would fail.

**Signature verification**: The account deletion control message is signed by the sender. Since the sender may have already deleted their server-side identity by the time the message is processed, verification falls back to the locally stored contact keys (which were established during a prior verified session).

**Server-first wipe**: The wipe flow deletes the server-side identity (`DELETE /v1/identity/me`) before clearing local keys. This ensures the auth token (which requires the local signing key) is still available for the API call and for retries if the first attempt fails.

**Tradeoff**: If the server is unreachable, the user must choose between retrying or deleting locally without server cleanup. The retry/skip dialog makes this explicit.

---

## ADR-14: Lifecycle guard against navigation ghost-clicks

**Decision**: Guard interactive actions (call button, send button) behind a `Lifecycle.State.RESUMED` check to prevent ghost-clicks during Compose Navigation exit animations.

**Why**: During a screen transition, both the outgoing and incoming composable are in the composition tree. The outgoing composable's lifecycle drops from `RESUMED` to `STARTED`, but its UI is still visible and tappable during the exit animation. If the outgoing screen's call button occupies the same position as the incoming screen's settings button, a tap triggers the call. Checking lifecycle state is the standard Compose Navigation pattern for preventing stale interactions.

**Tradeoff**: None significant. The check is a single property read and is the idiomatic solution.
