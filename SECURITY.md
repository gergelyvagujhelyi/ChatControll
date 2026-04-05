# Security Notes & Threat Model

## Cryptographic Design

### Key Establishment (Hybrid)
```
shared_secret = HKDF-SHA256(
    IKM = X25519(local_priv, remote_pub) || ML-KEM-768.Encapsulate(remote_ek),
    salt = "ChatControll-v1-session",
    info = "hybrid-key-establishment",
    length = 64
)
send_key = shared_secret[0:32]
recv_key = shared_secret[32:64]
```

- **Classical component**: X25519 (Curve25519 ECDH). Well-vetted, widely deployed.
- **Post-quantum component**: ML-KEM-768 (NIST FIPS 203) via Bouncy Castle 1.79+. Production post-quantum key encapsulation is active for all hybrid sessions.
- **Key derivation**: HKDF-SHA256 (RFC 5869) combines both shared secrets.
- **Payload encryption**: AES-256-GCM with random 12-byte nonces.

### Identity
- **Signing**: Ed25519 (used for identity assertions and future message authentication).
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
- Encrypted message envelopes in transit (deleted after delivery ACK)
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

### Key Rotation (v0.3.3+)
`rotateIdentityKeys()` generates new Ed25519 + X25519 + ML-KEM-768 keys and registers them with the relay server. The protocol uses crash-safe staged promotion:
1. New keys are staged locally before the server call.
2. Server validates a proof-of-possession signature (new key signs itself).
3. On server acceptance, staged keys are promoted to active.
4. On app crash between server acceptance and local promotion, the next launch detects staged keys and auto-promotes them.
5. All session caches are invalidated — peers re-establish on next message.

**Remaining work**: No automated rotation schedule and no old-key grace period for in-flight messages.

### Session Reset Protocol (v0.3.5+)
When a peer rotates their identity keys, the recipient's ratchet state becomes stale and messages cannot be decrypted. The session reset protocol handles this:
1. After `MAX_DECRYPT_RETRIES` (3) failed decrypt attempts, a `session_reset` control message is sent to the sender via the existing message pipeline (store-and-forward, guaranteed delivery even if the sender is offline).
2. The control message is signed with the recipient's current key and verified by the sender against the server's latest key bundle (handles the case where the verifier's locally stored key is outdated).
3. On receiving the signal, the sender's contact record is updated with the peer's new keys, the stale session is cleared, and the conversation is blocked (`needsSessionReset` flag).
4. The sender must manually tap "Re-establish session" in the chat UI, which fetches the peer's new key bundle, creates a fresh ratchet session, and unlocks sending.
5. Deduplication prevents multiple `session_reset` signals to the same peer; the tracking is cleared when a successful decrypt from that peer occurs.

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
Messages are signed with Ed25519 before sending. The recipient verifies the signature against the sender's stored public signing key before decryption. Messages from older clients without signatures are still accepted for backward compatibility.

### Call Signal Authentication (v0.3.1+)
Call signaling messages (offer, answer, ICE candidates) are signed with Ed25519. The recipient verifies the signature against the sender's public signing key before processing. This prevents call signal injection by a compromised relay server. Since v0.3.8, if the sender is not yet a local contact (e.g. newly added contact with no prior messages), the key bundle is fetched from the server and the contact is persisted only after signature verification succeeds.

### Signature Verification Robustness (v0.3.1+)
When a signed message or call signal arrives from an unknown sender, the client fetches the sender's key bundle from the server before verification. If the sender cannot be resolved, the signal is silently dropped. For call signals, the fetched contact is kept in memory during verification and only persisted to the database after the signature is confirmed valid (v0.3.8).

### PQC Negotiation & Downgrade Protection (v0.3.4+)
- **Symmetric encapsulation**: Either party can initiate ML-KEM-768 encapsulation. If a message carries KEM ciphertext, the recipient decapsulates; otherwise, the recipient encapsulates toward the sender's public encapsulation key. This eliminates the prior bug where only the lexicographic initiator could start a hybrid session.
- **Re-establishment on failure**: If decryption fails on a message with KEM ciphertext and an existing PQC session, the client re-establishes the session using the inbound KEM ciphertext before retrying.
- **PQC downgrade rejection**: If a contact was previously established as a hybrid PQ session, any subsequent classical-only session establishment is rejected. This prevents a MITM from silently stripping PQC protection.

### Base64 Input Validation (v0.3.4+)
All `Base64.decode` calls on externally-received data (key bundles, KEM ciphertext, message envelopes) are wrapped in try/catch. Malformed Base64 from the server or a peer is logged and rejected rather than crashing the app.

### Call Signal Reliability (v0.3.4–0.3.8)
Call signaling has been progressively hardened:
- **v0.3.4**: `rejectCall()` and `hangup()` send the signaling message before tearing down local call state, so the peer always receives reject/hangup.
- **v0.3.7**: Call signals now have FCM push fallback and server-side buffering (30s TTL) for offline recipients. The client checks the `delivered` field from `CallSignalResponse` instead of assuming delivery on HTTP success. `sendSignal()` only retries on network errors — if the server accepted but couldn't deliver (recipient offline), the signal is already buffered and FCM push is sent, so client retries would be redundant. `hangup()`/`rejectCall()` end the call UI immediately and send the signal fire-and-forget in the background (no UI blocking). A 35s ringing timeout ends unanswered calls with `UNAVAILABLE` status.
- **v0.3.8**: Calls to newly added contacts (no prior messages) no longer silently fail. Unknown callers are resolved by fetching their key bundle from the server, with signature verification before persisting the contact. Contact resolution runs outside the signal mutex to avoid blocking ICE candidate processing during network requests.

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
- [x] Certificate pinning with SPKI hashes
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
- [x] Per-challenge TURN nonce rotation (RFC 5389 compliant)
- [x] ICE candidate bounds (max 100 pending per call)
- [x] Certificate pinning with real SPKI hashes
- [x] Ratchet state persistence (survive app restart) — sessions persisted to EncryptedSharedPreferences
- [x] PQC downgrade rejection — classical-only re-establishment blocked for hybrid contacts
- [x] Base64 input validation on all externally-received key material
- [x] Call signal reliability — FCM push fallback, server-side buffering, ringing timeout, delivery status check
- [x] Session reset on key rotation — peers notified and blocked until re-keyed
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
