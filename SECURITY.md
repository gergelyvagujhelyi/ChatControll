# Security Notes & Threat Model

## Cryptographic Design

### Key Establishment (Hybrid)
```
shared_secret = HKDF-SHA256(
    IKM = X25519(local_priv, remote_pub) || ML-KEM-768.Encapsulate(remote_ek),
    salt = "ChatControll-v1-ratchet-init",
    info = "hybrid-key-establishment",
    length = 32
)
chain_material = HKDF-SHA256(
    IKM = shared_secret,
    salt = "ChatControll-v1-chains",
    info = "bidirectional-chains",
    length = 64
)
send_key = chain_material[0:32]   (initiator)
recv_key = chain_material[32:64]  (initiator)
```

- **Classical component**: X25519 (Curve25519 ECDH). Well-vetted, widely deployed.
- **Post-quantum component**: ML-KEM-768 (NIST FIPS 203) via Bouncy Castle 1.79+. Production post-quantum key encapsulation is active for all hybrid sessions.
- **Key derivation**: HKDF-SHA256 (RFC 5869) combines both shared secrets.
- **Payload encryption**: AES-256-GCM with random 12-byte nonces.

### Identity
- **Classical signing**: Ed25519 (identity assertions, message authentication, auth tokens).
- **Post-quantum signing**: ML-DSA-65 (NIST FIPS 204) via Bouncy Castle 1.79+. Used for hybrid dual-signing of auth tokens, messages, and call signals alongside Ed25519.
- **Key agreement**: X25519 (used for session establishment).
- **Storage**: EncryptedSharedPreferences backed by Android Keystore AES-256-GCM master key.

### Database Encryption
- SQLCipher AES-256, keyed from Android Keystore.

## Threat Model

### Assets
1. Message plaintext
2. Identity private keys
3. Session keys
4. Contact graph (who talks to whom)
5. Conversation metadata (timestamps, message counts)

### Threat Actors

| Actor | Capability | Mitigations |
|-------|-----------|-------------|
| **Passive network observer** | Sees encrypted traffic between device and relay server | TLS 1.3, certificate pinning (config ready), encrypted payloads inside TLS |
| **Relay server operator** | Sees encrypted envelopes, routing metadata, timing | E2E encryption means server cannot read content. Metadata minimization: no PII stored, messages deleted after ACK |
| **Stolen/seized device** | Physical access to device storage | SQLCipher database encryption, EncryptedSharedPreferences, Android Keystore for key material, FLAG_SECURE, auto-wipe option |
| **Quantum adversary (future)** | Harvest-now-decrypt-later of key exchanges | Hybrid key establishment with ML-KEM-768 (Bouncy Castle 1.79+). Production PQC active since v0.2.0 |
| **Malicious contact** | Can send messages, attempt abuse | Share-code-based discovery limits spam. Block/report mechanisms (future). No address book exposure |
| **App store supply chain** | Modified APK | Code signing, reproducible builds (future), ProGuard/R8 obfuscation |

### What the Server Knows
- User ID (random, no PII)
- Public key bundle
- FCM push token (can be correlated to Google account — see limitations)
- Encrypted message envelopes in transit (deleted after delivery ACK, or purged after 30 days if undelivered)
- Timing of message submission and retrieval

### What the Server Does NOT Know
- Message content
- Contact names or display names
- Phone numbers, emails, real names
- Which messages belong to which conversation (from the server's perspective, it's just sender → recipient)

## Known Limitations & Risks

### ML-KEM-768 via Bouncy Castle
`BouncyCastlePqcProvider` uses Bouncy Castle 1.79+ for ML-KEM-768 (NIST FIPS 203). This provides real post-quantum key encapsulation. The hybrid X25519 + ML-KEM design means both classical and quantum-resistant security layers are active. Note: Bouncy Castle's ML-KEM has not yet undergone FIPS certification for Android. For the highest assurance, evaluate NIST-certified modules as they become available.

### Forward Secrecy via Double Ratchet
The `RatchetSessionManager` implements a Signal-style Double Ratchet:
- **Per-message forward secrecy**: Each message uses a unique key derived from a symmetric chain ratchet. Used keys are deleted.
- **Break-in recovery**: DH ratchet steps generate new ephemeral X25519 keypairs, so even if session state is compromised, future messages become secure after the next ratchet step.
- **Out-of-order tolerance**: Up to 256 skipped message keys are cached for messages that arrive out of order.
- **Persistence**: Ratchet session state (root key, chain keys, message counters, skipped keys) is persisted to EncryptedSharedPreferences and survives app restarts. Session-to-contact mapping is also persisted, so ongoing conversations resume without re-keying. Full DB-backed persistence for ratchet chains is a future improvement for multi-device support.

### Key Rotation (v0.3.3+, crash recovery hardened v0.4.1)
`rotateIdentityKeys()` generates new Ed25519 + X25519 + ML-KEM-768 + ML-DSA-65 keys and registers them with the relay server. The protocol uses crash-safe staged promotion:
1. New keys are staged locally before the server call.
2. Server validates proof-of-possession signatures (Ed25519 and ML-DSA-65 new keys sign themselves).
3. On server acceptance, a `server_confirmed` flag is persisted synchronously, then staged keys are promoted to active.
4. On app crash between staging and server call, the next launch detects staged keys without `server_confirmed` and discards them (the server still has the old keys).
5. On app crash between server acceptance and local promotion, the next launch detects staged keys with `server_confirmed` and promotes them.
6. All session caches are invalidated — peers re-establish on next message.

**Remaining work**: No automated rotation schedule and no old-key grace period for in-flight messages.

### Session Reset Protocol (v0.3.5+)
When a peer rotates their identity keys, the recipient's ratchet state becomes stale and messages cannot be decrypted. The session reset protocol handles this:
1. After `MAX_DECRYPT_RETRIES` (3) failed decrypt attempts, a `session_reset` control message is sent to the sender via the existing message pipeline (store-and-forward, guaranteed delivery even if the sender is offline).
2. The control message is signed with the recipient's current key and verified by the sender against the server's latest key bundle (handles the case where the verifier's locally stored key is outdated).
3. On receiving the signal, the sender's contact record is updated with the peer's new keys, the stale session is cleared, and the conversation is blocked (`needsSessionReset` flag).
4. The sender must manually tap "Re-establish session" in the chat UI, which fetches the peer's new key bundle, creates a fresh ratchet session, and unlocks sending.
5. Deduplication prevents multiple `session_reset` signals to the same peer; the tracking is cleared when a successful decrypt from that peer occurs.

### Account Deletion Protocol (v0.3.9+)
When a user wipes all local data, the app notifies contacts and cleans up server-side state:
1. An `account_deleted` control message (distinct from `session_reset`) is sent to every contact via the message pipeline.
2. The server-side identity is deleted (`DELETE /v1/identity/me`) **before** local keys are wiped, so the auth token remains valid for the API call.
3. If the server is unreachable, the user is prompted to retry or skip server deletion. Retrying preserves local keys until the server call succeeds.
4. On the recipient side, the `account_deleted` control message sets a `peerDeleted` flag on the conversation. This permanently blocks sending and displays "Peer deleted their account" in the chat UI.
5. Signature verification of the `account_deleted` message falls back to locally stored contact keys (from a prior verified session), since the sender's server-side identity may already be deleted by the time the message is processed.

### Key Change Events in Chat History (v0.3.9+)
Key rotation and account deletion events are recorded as messages in the local chat history:
- **Local key rotation**: When you rotate your keys, a `KEY_ROTATED_LOCAL` event is inserted into each contact's conversation.
- **Remote key rotation**: When a peer's `session_reset` control message is processed, a `KEY_ROTATED_REMOTE` event is recorded.
- **Account deletion**: When a peer's `account_deleted` control message is processed, an `ACCOUNT_DELETED` event is recorded.
These events are rendered as centered, non-interactive items with distinct icons, giving users a clear audit trail of key changes in each conversation.

### Device Loss = Identity Loss
Since identity lives only on the device, losing the device means losing:
- The identity (cannot prove you are the same user)
- All message history
- All contacts
This is a deliberate privacy choice. Optional encrypted backup (e.g., to user-controlled cloud storage with a passphrase) could be added as an opt-in feature.

### FCM Token Linkability
The FCM push token is tied to the device's Google account. A sophisticated adversary (Google, or someone with access to Google's systems) could correlate the FCM token with a real identity. Mitigations:
- Use a push proxy service that breaks the direct link (future)
- Support alternative push mechanisms (UnifiedPush, WebSocket fallback)

### Message Authentication (v0.3.0+)
Messages are signed with Ed25519 before sending. The recipient verifies the signature against the sender's stored public signing key before decryption. Since v0.3.10, unsigned messages are unconditionally rejected — the legacy backward-compatibility exemption for contacts with `signatureRequired=false` has been removed to prevent impersonation via unsigned message injection.

### Call Signal Authentication (v0.3.1+)
Call signaling messages (offer, answer, ICE candidates) are signed with Ed25519. The recipient verifies the signature against the sender's public signing key before processing. This prevents call signal injection by a compromised relay server. Since v0.3.8, if the sender is not yet a local contact (e.g. newly added contact with no prior messages), the key bundle is fetched from the server and the contact is persisted only after signature verification succeeds. Since v0.4.0, call signals are also dual-signed with ML-DSA-65 when the sender has PQC signing keys, with the same mandatory enforcement as messages (PQC-capable senders cannot omit ML-DSA signatures).

### Signature Verification Robustness (v0.3.1+)
When a signed message or call signal arrives from an unknown sender, the client fetches the sender's key bundle from the server before verification. If the sender cannot be resolved, the signal is silently dropped. For call signals, the fetched contact is kept in memory during verification and only persisted to the database after the signature is confirmed valid (v0.3.8).

### PQC Negotiation & Downgrade Protection (v0.3.4+)
- **Symmetric encapsulation**: Either party can initiate ML-KEM-768 encapsulation. If a message carries KEM ciphertext, the recipient decapsulates; otherwise, the recipient encapsulates toward the sender's public encapsulation key. This eliminates the prior bug where only the lexicographic initiator could start a hybrid session.
- **Re-establishment on failure**: If decryption fails on a message with KEM ciphertext and an existing PQC session, the client re-establishes the session using the inbound KEM ciphertext before retrying.
- **PQC downgrade rejection**: If a contact was previously established as a hybrid PQ session, any subsequent classical-only session establishment is rejected. This prevents a MITM from silently stripping PQC protection.

### Base64 Input Validation (v0.3.4+)
All `Base64.decode` calls on externally-received data (key bundles, KEM ciphertext, message envelopes) are wrapped in try/catch. Malformed Base64 from the server or a peer is logged and rejected rather than crashing the app.

### Call Encryption (v0.3.9+, hybrid PQC v0.4.0)
Voice call media and signaling are end-to-end encrypted:

**Signal encryption** (v0.4.0): Call signal payloads (SDP offers/answers, ICE candidates) are encrypted with AES-256-GCM using per-call ephemeral keys derived from session keys via SHAKE-256 KDF. This bypasses the Double Ratchet to avoid advancing the message chain — call signals are ephemeral and may be lost/reordered.

**Hybrid PQC key agreement** (v0.4.0): Call session keys are derived independently from the messaging ratchet to avoid corrupting ratchet state:
```
call_shared_secret = SHAKE-256-KDF(
    IKM = X25519(local_priv, remote_pub) || ML-KEM-768.Encapsulate(remote_ek),
    salt = "ChatControll-v1-call-init",
    info = "hybrid-key-establishment",
    length = 32
)
chain_material = SHAKE-256-KDF(call_shared_secret, "ChatControll-v1-call-chains", "bidirectional-chains", 64)
send_key = chain_material[0:32]  (initiator) / chain_material[32:64]  (responder)
```
The entire call encryption path uses SHAKE-256 (SHA-3 family) instead of HMAC-SHA-256 to avoid SHA-2 dependencies in the post-quantum path. KEM ciphertext is attached to the `call_offer` signal and decapsulated by the responder.

**Media encryption**: Frame-level AES-GCM via WebRTC FrameCryptor API. Media keys are derived from session keys using SHAKE-256 KDF with the call ID as salt. The commutative XOR of send/receive keys ensures both peers derive the same media key.
- **Implementation**: `WebRtcEngine.enableFrameEncryption(key)` creates separate sender and receiver `FrameCryptor` instances. Encryption state changes are surfaced via callback for UI feedback.
- **Cleanup**: Frame cryptors are disposed alongside the peer connection to prevent key material leaks.

### ML-DSA-65 Dual-Signing (v0.4.0)
All auth tokens, messages, and call signals are now dual-signed with Ed25519 + ML-DSA-65 when PQC keys are available:
- **Auth tokens**: Format extended to `<user_id>.<timestamp_ms>.<ed25519_sig>.<mldsa_sig>`. The server verifies both signatures when the user has a PQC signing key registered.
- **Messages**: `pqcSignature` field added to `SendMessageRequest`. Recipients enforce mandatory ML-DSA verification for PQC-capable senders.
- **Call signals**: `pqcSignature` field added to `CallSignalRequest`. Same enforcement as messages.
- **Anti-downgrade**: If a user has a PQC signing key, it cannot be cleared via key rotation. An attacker who compromises Ed25519 cannot downgrade the user from hybrid PQC+Ed25519 auth back to Ed25519-only.
- **Key rotation**: Proof-of-possession is required for both Ed25519 (new key signs itself) and ML-DSA-65 (new PQC key signs its own Base64 encoding).

### Call Ringing Confirmation (v0.4.0)
A `call_ringing` signal is sent by the callee when it receives a `call_offer`, confirming the device is actively ringing. The caller transitions from "Connecting..." to "Ringing..." only after receiving this confirmation, providing accurate call status to the user.

### Call Signal Reliability (v0.3.4–0.3.8)
Call signaling has been progressively hardened:
- **v0.3.4**: `rejectCall()` and `hangup()` send the signaling message before tearing down local call state, so the peer always receives reject/hangup.
- **v0.3.7**: Call signals now have FCM push fallback and server-side buffering (30s TTL) for offline recipients. The client checks the `delivered` field from `CallSignalResponse` instead of assuming delivery on HTTP success. `sendSignal()` only retries on network errors — if the server accepted but couldn't deliver (recipient offline), the signal is already buffered and FCM push is sent, so client retries would be redundant. `hangup()`/`rejectCall()` end the call UI immediately and send the signal fire-and-forget in the background (no UI blocking). A 35s ringing timeout ends unanswered calls with `UNAVAILABLE` status.
- **v0.3.8**: Calls to newly added contacts (no prior messages) no longer silently fail. Unknown callers are resolved by fetching their key bundle from the server, with signature verification before persisting the contact. Contact resolution runs outside the signal mutex to avoid blocking ICE candidate processing during network requests.

### Rate Limiter Hardening (server v0.3.4)
- **Disconnect bypass fix**: Per-user WebSocket signal rate limit state is no longer cleared on disconnect. Previously, a malicious user could reset their quota by reconnecting. Stale entries are pruned periodically (idle > 5 min) instead.
- **Pruning performance**: Rate limit pruning in both WebSocket and REST call signaling routers now uses a high-water mark (2000 entries) with time-gated scans (once per 60s) to avoid O(N) dictionary iteration under the global lock on every request.

### Security Hardening (v0.3.10 / server v0.3.5)
- **Key material zeroization**: Private signing keys, PQC decapsulation keys, classical/PQC shared secrets, HKDF inputs, and chain material are zeroized after use to limit lifetime in memory.
- **Ratchet state invalidation on failure**: If persistence fails after ratchet mutation, the session is invalidated (removed from memory and disk) to force re-establishment, preventing key/nonce reuse.
- **Ratchet header validation**: `messageNumber` and `previousChainLength` from untrusted input are validated as non-negative before use.
- **PQC downgrade on failure**: If KEM ciphertext is present but session re-establishment fails, the message is rejected rather than silently falling back to classical-only keys.
- **Server TOCTOU fix**: Message queue depth check uses `FOR UPDATE` row lock on the recipient's Identity to prevent concurrent requests bypassing `MAX_PENDING_MESSAGES_PER_USER`.
- **Share code collision on rotation**: Key rotation now catches `IntegrityError` on commit, matching bootstrap's collision protection.
- **Nginx security headers**: HSTS, CSP, X-Frame-Options, X-Content-Type-Options, and Referrer-Policy added.
- **Rate limiter multi-worker compensation**: Per-worker IP rate limit is divided by `UVICORN_WORKERS` count.
- **WebSocket DB session scoping**: DB session is explicitly closed after auth to avoid pool exhaustion on long-lived connections.

### Security Hardening (v0.4.0)
- **endCallGuard race fix**: The guard preventing double-dispose of WebRTC resources is now reset at the start of each new call, preventing leaked native resources and stuck audio mode when calls start within the 2s cleanup window.
- **Late call_answer rejection**: The `call_answer` handler now checks for terminal call status, preventing a queued answer from reviving a call the user already hung up.
- **SPKI OID validation**: Server-side ML-DSA-65 SPKI header parsing now validates the OID bytes, not just length, preventing garbage SPKI headers from being accepted.
- **PQC signing key persistence**: Contact PQC signing keys are now stored during session upgrade in `tryEstablishSession`, closing a gap where PQC signatures were never verified for contacts upgraded via inbound messages.
- **Control message retry on PQC sig missing**: Control messages (session_reset, account_deleted) with missing PQC signatures are skipped for retry on next sync to handle the key propagation race. Since v0.4.1, retries are bounded by `MAX_PQC_SIG_MISS_RETRIES` (3) to prevent infinite retry loops.
- **Process kill after wipe**: `wipeLocal()` now calls `exitProcess(0)` after database close to prevent the dead `@Singleton AppDatabase` from crashing subsequent DAO access.
- **ML-DSA private key cleanup**: `BouncyCastlePqcProvider.sign()` now calls `tryDestroy()` on the reconstructed private key JCA object.
- **Auth token deduplication**: Dual-signed token generation extracted from `KtorApiService`/`WebSocketClient` into `KeyManager.generateAuthToken()` to prevent implementations drifting apart.
- **Unconditional secret zeroization**: `pqcSecret` is now always zeroized (was conditional on `isPqcEstablished`).
- **ML-DSA failure logging**: Silent ML-DSA signing failures in `SessionResetSender`, `MessageRepositoryImpl`, and `IdentityRepositoryImpl` now log warnings.
- **Schema 6.json restored**: Retroactive modification of the v6 schema (which broke migration testing) was reverted.
- **404 handling in deleteIdentity**: Fragile string matching replaced with direct HTTP status code check.

### Certificate Pinning
Network security config includes SHA-256 SPKI pin hashes for the relay server's leaf certificate and intermediate CA. Pins expire 2028-10-01 and must be rotated before expiry.

## Security Checklist

- [x] Messages encrypted before leaving device (AES-256-GCM)
- [x] Hybrid key establishment framework (X25519 + PQC interface)
- [x] No plaintext in logs or crash reports
- [x] No PII required for registration
- [x] SQLCipher encrypted database
- [x] Android Keystore for key material
- [x] FLAG_SECURE for screen security
- [x] No cloud backup by default
- [x] Lock screen notification previews hidden by default
- [x] No telemetry or third-party trackers
- [x] ProGuard/R8 enabled for release builds
- [x] Production ML-KEM-768 library (Bouncy Castle 1.79+)
- [x] Double Ratchet for forward secrecy
- [x] Real relay server (Python/FastAPI) with rate limiting
- [x] WebSocket real-time delivery
- [x] Message signatures (Ed25519) — sender signs, recipient verifies
- [x] Signed request authentication (Ed25519 token-based, prevents spoofing)
- [x] Ephemeral TURN credentials (HMAC-based, per-session)
- [x] Metrics endpoint authentication (bearer token)
- [x] WebSocket call signal validation and rate limiting
- [x] Debug logging gated behind BuildConfig.DEBUG
- [x] Call signal signatures (Ed25519) — prevents signal injection
- [x] Per-challenge TURN nonce rotation (RFC 5389 compliant, handled by coturn)
- [x] ICE candidate bounds (max 100 pending per call)
- [x] Certificate pinning with real SPKI hashes
- [x] Ratchet state persistence (survive app restart) — sessions persisted to EncryptedSharedPreferences
- [x] PQC downgrade rejection — classical-only re-establishment blocked for hybrid contacts
- [x] Base64 input validation on all externally-received key material
- [x] Call signal reliability — FCM push fallback, server-side buffering, ringing timeout, delivery status check
- [x] Session reset on key rotation — peers notified and blocked until re-keyed
- [x] Account deletion protocol — contacts notified, server identity deleted, send permanently blocked
- [x] Key change audit trail — rotation and deletion events visible in chat history
- [x] Call frame encryption — AES-GCM via WebRTC FrameCryptor, HKDF-derived keys
- [x] Key material zeroization — signing keys, shared secrets, chain material wiped after use
- [x] Mandatory message signatures — unsigned messages rejected unconditionally
- [x] Nginx security headers — HSTS, CSP, X-Frame-Options, nosniff, Referrer-Policy
- [x] Server TOCTOU protection — row-level locking on message queue depth check
- [x] Ratchet header validation — bounds checking on untrusted messageNumber/previousChainLength
- [x] ML-DSA-65 dual-signing — auth tokens, messages, and call signals signed with Ed25519 + ML-DSA-65
- [x] Hybrid PQC call encryption — ML-KEM-768 key agreement + SHAKE-256 KDF for call session keys
- [x] PQC signing key anti-downgrade — server prevents clearing PQC signing key once set
- [x] PQC proof-of-possession — ML-DSA key rotation requires signing proof
- [x] Call ringing confirmation — caller shows accurate Ringing status based on peer signal
- [ ] Push proxy to break FCM linkability
- [x] Key rotation protocol — `rotateIdentityKeys()` with crash-safe staged promotion
- [ ] Automated key rotation schedule + old-key grace period
- [ ] Reproducible builds

## Abuse Controls

Abuse controls that preserve privacy:

1. **Share-code gating**: You cannot message someone without their share code. This prevents unsolicited spam.
2. **Rate limiting** (server-side): The relay server rate-limits message submission to 60 messages/minute per user ID, with a 1000-message pending queue cap per recipient.
3. **Local block list** (future): Users can block contacts locally. Blocked contacts' messages are silently dropped.
4. **Report mechanism** (future): Users can forward an encrypted envelope + decrypted content to an abuse review system. This is opt-in and requires the reporter to reveal the message content.
5. **No global directory**: There is no way to enumerate users or discover contacts without knowing their share code.
