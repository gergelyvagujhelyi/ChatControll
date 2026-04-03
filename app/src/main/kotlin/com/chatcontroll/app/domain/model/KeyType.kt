package com.chatcontroll.app.domain.model

/**
 * The type of cryptographic keys used for the user's identity.
 */
enum class KeyType {
    /** Classical X25519 + Ed25519 only. Widely tested, fast. */
    CLASSICAL,

    /** Hybrid: classical X25519 + post-quantum ML-KEM-768 (NIST FIPS 203). Future-proof against quantum computers. */
    HYBRID_POST_QUANTUM,
}
