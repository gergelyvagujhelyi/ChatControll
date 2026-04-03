package com.chatcontroll.app.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classical cryptography using Bouncy Castle for Ed25519 and X25519.
 *
 * Using the BC provider ensures these algorithms work on all API levels
 * (the platform JCA only added Ed25519/X25519 in API 33).
 */
@Singleton
class AndroidClassicalKeyAgreement @Inject constructor() : ClassicalKeyAgreement {

    private val provider = BouncyCastleProvider.PROVIDER_NAME

    override fun generateSigningKeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("Ed25519", provider)
        val kp = kpg.generateKeyPair()
        return kp.public.encoded to kp.private.encoded
    }

    override fun generateKeyAgreementKeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("X25519", provider)
        val kp = kpg.generateKeyPair()
        return kp.public.encoded to kp.private.encoded
    }

    override fun agree(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519", provider)
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val pubKey = kf.generatePublic(X509EncodedKeySpec(remotePublicKey))
        val ka = KeyAgreement.getInstance("X25519", provider)
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    override fun sign(data: ByteArray): ByteArray {
        throw UnsupportedOperationException(
            "Direct signing requires KeyManager to supply the private key. Use KeyManager.sign() instead."
        )
    }

    override fun verify(data: ByteArray, signature: ByteArray, publicSigningKey: ByteArray): Boolean {
        val kf = KeyFactory.getInstance("Ed25519", provider)
        val pubKey = kf.generatePublic(X509EncodedKeySpec(publicSigningKey))
        val sig = Signature.getInstance("Ed25519", provider)
        sig.initVerify(pubKey)
        sig.update(data)
        return sig.verify(signature)
    }
}
