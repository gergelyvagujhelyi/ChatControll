package com.chatcontroll.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.chatcontroll.app.data.remote.WebSocketClient
import com.chatcontroll.app.data.repository.IdentityRepositoryImpl
import com.chatcontroll.app.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class ChatControllApp : Application(), Configuration.Provider {

    companion object {
        init {
            // Must load before Hilt injects the database (DI triggers before onCreate)
            System.loadLibrary("sqlcipher")

            // Android ships a stripped-down BC provider that lacks Ed25519/X25519/Kyber.
            // Replace it with the full Bouncy Castle 1.79 provider.
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var webSocketClient: WebSocketClient
    @Inject lateinit var identityRepository: IdentityRepositoryImpl

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Recover from interrupted key rotation before any identity access
        appScope.launch { identityRepository.recoverFromInterruptedKeyRotation() }

        scheduleSyncWorker()

        // Connect WebSocket for real-time delivery (no-op if using mock backend)
        webSocketClient.connect()
    }

    private fun scheduleSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "message_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest,
        )
    }
}
