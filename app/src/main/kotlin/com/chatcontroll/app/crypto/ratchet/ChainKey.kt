package com.chatcontroll.app.crypto.ratchet

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A symmetric ratchet chain key as used in the Double Ratchet algorithm.
 *
 * Each chain key derives:
 * - A message key (used once to encrypt a single message, then discarded)
 * - A next chain key (advances the chain, providing forward secrecy)
 *
 * After deriving the message key, the old chain key is deleted.
 */
data class ChainKey(
    val key: ByteArray,
    val index: Int,
) {
    /** Derive the message key for this chain step. */
    fun messageKey(): ByteArray {
        return hmacSha256(key, MESSAGE_KEY_SEED)
    }

    /** Advance the chain to the next step. */
    fun next(): ChainKey {
        check(index < Int.MAX_VALUE) { "Chain key index overflow" }
        return ChainKey(
            key = hmacSha256(key, CHAIN_KEY_SEED),
            index = index + 1,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainKey) return false
        return key.contentEquals(other.key) && index == other.index
    }

    override fun hashCode(): Int = key.contentHashCode() * 31 + index

    companion object {
        private val MESSAGE_KEY_SEED = byteArrayOf(0x01)
        private val CHAIN_KEY_SEED = byteArrayOf(0x02)

        private fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(input)
        }
    }
}
