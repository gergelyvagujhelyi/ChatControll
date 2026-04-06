package com.chatcontroll.app.crypto

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation
import org.bouncycastle.jcajce.spec.KEMExtractSpec
import org.bouncycastle.jcajce.spec.KEMGenerateSpec
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production ML-KEM-768 provider backed by Bouncy Castle 1.79+.
 *
 * ML-KEM-768 is standardized as NIST FIPS 203. Bouncy Castle implements it
 * under the "ML-KEM" algorithm name with [MLKEMParameterSpec.ml_kem_768] parameters.
 *
 * Since BC 1.78 the PQC algorithms are bundled in the main bcprov jar.
 * The full [BouncyCastleProvider] (replacing Android's stripped version)
 * includes both classical and PQC algorithms.
 */
@Singleton
class BouncyCastlePqcProvider @Inject constructor() : PqcProvider {

    init {
        // Ensure full BC provider is registered (Android's built-in BC is stripped)
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    override fun generateKemKeyPair(): KemKeyPair {
        val kpg = KeyPairGenerator.getInstance(ALGORITHM, PROVIDER)
        kpg.initialize(MLKEMParameterSpec.ml_kem_768, SecureRandom())
        val kp = kpg.generateKeyPair()
        return KemKeyPair(
            encapsulationKey = kp.public.encoded,
            decapsulationKey = kp.private.encoded,
        )
    }

    override fun encapsulate(encapsulationKey: ByteArray): KemEncapsulation {
        val kf = KeyFactory.getInstance(ALGORITHM, PROVIDER)
        val publicKey = kf.generatePublic(X509EncodedKeySpec(encapsulationKey))

        val keyGen = KeyGenerator.getInstance(ALGORITHM, PROVIDER)
        keyGen.init(KEMGenerateSpec(publicKey, "AES"), SecureRandom())

        val secretKey = keyGen.generateKey() as SecretKeyWithEncapsulation
        return KemEncapsulation(
            ciphertext = secretKey.encapsulation,
            sharedSecret = secretKey.encoded,
        )
    }

    override fun decapsulate(ciphertext: ByteArray, decapsulationKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance(ALGORITHM, PROVIDER)
        val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(decapsulationKey))

        val keyGen = KeyGenerator.getInstance(ALGORITHM, PROVIDER)
        keyGen.init(KEMExtractSpec(privateKey, ciphertext, "AES"))

        val secretKey = keyGen.generateKey()
        return secretKey.encoded
    }

    override fun generateSigningKeyPair(): DsaKeyPair {
        val kpg = KeyPairGenerator.getInstance(DSA_ALGORITHM, PROVIDER)
        kpg.initialize(MLDSAParameterSpec.ml_dsa_65, SecureRandom())
        val kp = kpg.generateKeyPair()
        return DsaKeyPair(
            publicKey = kp.public.encoded,
            privateKey = kp.private.encoded,
        )
    }

    override fun sign(data: ByteArray, privateKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance(DSA_ALGORITHM, PROVIDER)
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val sig = Signature.getInstance(DSA_ALGORITHM, PROVIDER)
        sig.initSign(privKey)
        sig.update(data)
        return sig.sign()
    }

    override fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        val kf = KeyFactory.getInstance(DSA_ALGORITHM, PROVIDER)
        val pubKey = kf.generatePublic(X509EncodedKeySpec(publicKey))
        val sig = Signature.getInstance(DSA_ALGORITHM, PROVIDER)
        sig.initVerify(pubKey)
        sig.update(data)
        return sig.verify(signature)
    }

    companion object {
        private const val ALGORITHM = "ML-KEM"
        private const val DSA_ALGORITHM = "ML-DSA"
        private const val PROVIDER = "BC"
    }
}
