package com.chatcontroll.app.crypto

import java.nio.ByteBuffer

/**
 * Length-prefix a byte array with a 4-byte big-endian length header.
 * Used in all signature payload construction to prevent field ambiguity.
 */
fun lengthPrefixed(data: ByteArray): ByteArray {
    return ByteBuffer.allocate(4).putInt(data.size).array() + data
}

/**
 * Build the canonical signature payload for message envelopes.
 * Both signing (sender) and verification (recipient) use this format:
 *
 *     LP(senderId) || LP(recipientId) || LP(nonce) || body
 *
 * where LP = [lengthPrefixed].
 */
fun buildMessageSigPayload(
    senderId: String,
    recipientId: String,
    nonce: ByteArray,
    body: ByteArray,
): ByteArray {
    return lengthPrefixed(senderId.toByteArray(Charsets.UTF_8)) +
        lengthPrefixed(recipientId.toByteArray(Charsets.UTF_8)) +
        lengthPrefixed(nonce) + body
}
