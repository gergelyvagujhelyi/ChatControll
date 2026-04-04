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
- **Post-quantum component**: ML-KEM-768 (NIST FIPS 203). Currently mocked — the `MockPqcProvider` provides **no quantum resistance**. It validates the hybrid flow only.
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
- **Limitation**: Ratchet state is currently held in memory. App restart requires re-keying. Persisting encrypted ratchet state to the database is a priority improvement.

### No Key Rotation
Identity keys are generated once and used indefinitely. A key rotation mechanism should be added for:
- Periodic re-keying
- Compromise recovery
- Device migration

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
Call signaling messages (offer, answer, ICE candidates) are signed with Ed25519. The recipient verifies the signature against the sender's stored public signing key before processing. This prevents call signal injection by a compromised relay server.

### Signature Verification Robustness (v0.3.1+)
When a signed message arrives from an unknown sender, the client attempts to establish a session (fetching the sender's key bundle) before verification. If the sender cannot be resolved, the message is silently dropped. This closes a bypass where signed messages from unknown contacts could skip verification.

### Certificate Pinning
Network security config includes SHA-256 SPKI pin hashes for the relay server's leaf certificate and intermediate CA. Pins expire 2027-10-01 and must be rotated before expiry.

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
- [ ] Ratchet state persistence (survive app restart)
- [ ] Push proxy to break FCM linkability
- [ ] Key rotation protocol
- [ ] Reproducible builds

## Abuse Controls

Abuse controls that preserve privacy:

1. **Share-code gating**: You cannot message someone without their share code. This prevents unsolicited spam.
2. **Rate limiting** (server-side): The relay server rate-limits message submission to 60 messages/minute per user ID, with a 1000-message pending queue cap per recipient.
3. **Local block list** (future): Users can block contacts locally. Blocked contacts' messages are silently dropped.
4. **Report mechanism** (future): Users can forward an encrypted envelope + decrypted content to an abuse review system. This is opt-in and requires the reporter to reveal the message content.
5. **No global directory**: There is no way to enumerate users or discover contacts without knowing their share code.
