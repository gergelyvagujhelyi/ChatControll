package com.chatcontroll.app.di

import com.chatcontroll.app.crypto.AndroidClassicalKeyAgreement
import com.chatcontroll.app.crypto.BouncyCastlePqcProvider
import com.chatcontroll.app.crypto.ClassicalKeyAgreement
import com.chatcontroll.app.crypto.PqcProvider
import com.chatcontroll.app.crypto.ratchet.RatchetSessionManager
import com.chatcontroll.app.domain.repository.CryptoEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    /**
     * Double Ratchet-based crypto engine with per-message forward secrecy.
     * Combines hybrid X25519 + ML-KEM key establishment with a Signal-style
     * ratchet for ongoing message encryption.
     *
     * To revert to the simpler static-session engine, bind HybridCryptoEngine instead.
     */
    @Binds
    @Singleton
    abstract fun bindCryptoEngine(impl: RatchetSessionManager): CryptoEngine

    @Binds
    @Singleton
    abstract fun bindClassicalKeyAgreement(impl: AndroidClassicalKeyAgreement): ClassicalKeyAgreement

    /** Production ML-KEM-768 via Bouncy Castle 1.79+. */
    @Binds
    @Singleton
    abstract fun bindPqcProvider(impl: BouncyCastlePqcProvider): PqcProvider
}
