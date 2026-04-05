package com.chatcontroll.app.crypto.ratchet

import android.util.Base64
import com.chatcontroll.app.crypto.ClassicalKeyAgreement
import com.chatcontroll.app.crypto.hkdfSha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Double Ratchet implementation following the Signal specification.
 *
 * Provides:
 * - **Forward secrecy**: Compromising current keys does not reveal past messages.
 * - **Break-in recovery**: After a DH ratchet step, new messages are secure even
 *   if previous session state was compromised.
 * - **Out-of-order tolerance**: Skipped message keys are cached (up to [RatchetState.MAX_SKIP]).
 *
 * The ratchet combines:
 * 1. A **DH ratchet** (X25519): new ephemeral keys on each turn of the conversation.
 * 2. A **symmetric ratchet** (HMAC-SHA256 chain): per-message key derivation within a turn.
 *
 * Each message is encrypted with AES-256-GCM using a unique message key derived from the chain.
 *
 * Reference: https://signal.org/docs/specifications/doubleratchet/
 */
class DoubleRatchet(
    private val classicalKeyAgreement: ClassicalKeyAgreement,
) {
    private val secureRandom = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Initialize a ratchet session as the initiator (Alice).
     *
     * Called after X3DH or initial key agreement. Alice knows Bob's ratchet public key
     * from his key bundle.
     */
    fun initAlice(
        sharedSecret: ByteArray,
        bobRatchetPublicKey: ByteArray,
    ): RatchetState {
        val aliceKeyPair = generateDhKeyPair()
        val dhOutput = classicalKeyAgreement.agree(aliceKeyPair.privateKey, bobRatchetPublicKey)

        val (rootKey, sendingChainKey) = kdfRootKey(sharedSecret, dhOutput)

        return RatchetState(
            dhKeyPair = aliceKeyPair,
            remoteDhPublicKey = bobRatchetPublicKey,
            rootKey = rootKey,
            sendingChainKey = ChainKey(sendingChainKey, 0),
            receivingChainKey = null,
        )
    }

    /**
     * Initialize a ratchet session as the responder (Bob).
     *
     * Bob uses his pre-existing ratchet keypair. He won't have a sending chain
     * until he receives Alice's first message and performs a DH ratchet.
     */
    fun initBob(
        sharedSecret: ByteArray,
        bobKeyPair: DhKeyPair,
    ): RatchetState {
        return RatchetState(
            dhKeyPair = bobKeyPair,
            remoteDhPublicKey = null,
            rootKey = sharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
        )
    }

    /**
     * Encrypt a plaintext message, advancing the sending chain.
     *
     * Returns the encrypted envelope and the ratchet header (which must be sent
     * alongside the ciphertext so the receiver can derive the correct key).
     */
    fun encrypt(state: RatchetState, plaintext: ByteArray): Pair<RatchetHeader, ByteArray> {
        val chainKey = state.sendingChainKey
            ?: throw IllegalStateException("No sending chain — session not fully initialized")

        val messageKey = chainKey.messageKey()
        val nextChain = chainKey.next()

        val header = RatchetHeader(
            publicKey = Base64.encodeToString(state.dhKeyPair.publicKey, Base64.NO_WRAP),
            previousChainLength = state.previousSendingChainLength,
            messageNumber = chainKey.index,
        )

        // Include header as AAD to prevent tampering (per Signal spec)
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        val ciphertext = aesGcmEncrypt(messageKey, plaintext, headerBytes)

        // Advance the sending chain
        state.sendingChainKey = nextChain

        // Zeroize the used message key
        messageKey.fill(0)

        return header to ciphertext
    }

    /**
     * Decrypt a received message, performing DH ratchet steps as needed.
     */
    fun decrypt(state: RatchetState, header: RatchetHeader, ciphertext: ByteArray): ByteArray {
        val senderPublicKey = try {
            Base64.decode(header.publicKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Invalid Base64 in ratchet header public key", e)
        }

        // Check if this message key was previously skipped
        val skippedMapKey = senderPublicKey.toHex() to header.messageNumber
        val skippedKey = state.skippedMessageKeys.remove(skippedMapKey)
        if (skippedKey != null) {
            state.skippedKeyTimestamps.remove(skippedMapKey)
            val skippedHeaderBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
            val plaintext = aesGcmDecrypt(skippedKey, ciphertext, skippedHeaderBytes)
            skippedKey.fill(0)
            return plaintext
        }

        // If the sender's DH key is new, perform a DH ratchet step
        if (state.remoteDhPublicKey == null || !senderPublicKey.contentEquals(state.remoteDhPublicKey!!)) {
            // Skip any remaining messages in the current receiving chain
            if (state.receivingChainKey != null) {
                skipMessageKeys(state, header.previousChainLength)
            }

            // DH ratchet step
            dhRatchetStep(state, senderPublicKey)
        }

        // Skip messages in the new receiving chain up to this message number
        skipMessageKeys(state, header.messageNumber)

        // Derive and consume the message key
        val chainKey = state.receivingChainKey
            ?: throw IllegalStateException("No receiving chain after ratchet step")

        val messageKey = chainKey.messageKey()
        state.receivingChainKey = chainKey.next()

        // Include header as AAD to verify integrity (per Signal spec)
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        val plaintext = aesGcmDecrypt(messageKey, ciphertext, headerBytes)
        messageKey.fill(0)
        return plaintext
    }

    private fun dhRatchetStep(state: RatchetState, newRemotePublicKey: ByteArray) {
        state.previousSendingChainLength = state.sendingChainKey?.index ?: 0
        state.remoteDhPublicKey = newRemotePublicKey

        // Derive new receiving chain
        val dhReceive = classicalKeyAgreement.agree(state.dhKeyPair.privateKey, newRemotePublicKey)
        val oldRootKey = state.rootKey
        val (rootKey1, receivingChainKey) = kdfRootKey(oldRootKey, dhReceive)
        oldRootKey.fill(0)
        dhReceive.fill(0)
        state.rootKey = rootKey1
        state.receivingChainKey = ChainKey(receivingChainKey, 0)

        // Zeroize old DH private key before replacing
        val oldPrivateKey = state.dhKeyPair.privateKey
        state.dhKeyPair = generateDhKeyPair()
        oldPrivateKey.fill(0)

        val dhSend = classicalKeyAgreement.agree(state.dhKeyPair.privateKey, newRemotePublicKey)
        val oldRootKey2 = state.rootKey
        val (rootKey2, sendingChainKey) = kdfRootKey(oldRootKey2, dhSend)
        oldRootKey2.fill(0)
        dhSend.fill(0)
        state.rootKey = rootKey2
        state.sendingChainKey = ChainKey(sendingChainKey, 0)
    }

    private fun skipMessageKeys(state: RatchetState, until: Int) {
        val chainKey = state.receivingChainKey ?: return
        if (until < chainKey.index || until - chainKey.index > RatchetState.MAX_SKIP) {
            throw SecurityException("Too many skipped messages (possible attack)")
        }

        // Purge expired skipped keys before adding new ones
        val now = System.currentTimeMillis()
        val expired = state.skippedKeyTimestamps.filter { now - it.value > RatchetState.SKIPPED_KEY_TTL_MS }.keys
        for (key in expired) {
            state.skippedMessageKeys.remove(key)?.fill(0)
            state.skippedKeyTimestamps.remove(key)
        }

        var current = chainKey
        while (current.index < until) {
            val remoteKeyHex = state.remoteDhPublicKey?.toHex() ?: break
            val mapKey = remoteKeyHex to current.index
            state.skippedMessageKeys[mapKey] = current.messageKey()
            state.skippedKeyTimestamps[mapKey] = now
            current = current.next()
        }
        state.receivingChainKey = current
    }

    /** KDF_RK: Derive new root key and chain key from root key + DH output. */
    private fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = hkdfSha256(
            ikm = dhOutput,
            salt = rootKey,
            info = "ChatControll-ratchet".toByteArray(),
            length = 64,
        )
        return derived.copyOfRange(0, 32) to derived.copyOfRange(32, 64)
    }

    private fun generateDhKeyPair(): DhKeyPair {
        val (pub, priv) = classicalKeyAgreement.generateKeyAgreementKeyPair()
        return DhKeyPair(publicKey = pub, privateKey = priv)
    }

    private fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        // Prepend nonce to ciphertext
        return nonce + ct
    }

    private fun aesGcmDecrypt(key: ByteArray, nonceAndCiphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        // 12-byte nonce + at least 16-byte GCM tag
        require(nonceAndCiphertext.size >= 28) { "Ciphertext too short" }
        val nonce = nonceAndCiphertext.copyOfRange(0, 12)
        val ct = nonceAndCiphertext.copyOfRange(12, nonceAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
