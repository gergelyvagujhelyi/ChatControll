package com.chatcontroll.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.domain.repository.MessageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageRepository: MessageRepository,
    private val messageDao: MessageDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Fetch any pending messages from the server
            messageRepository.fetchPendingFromServer()

            // Clean up expired messages
            messageDao.deleteExpired(System.currentTimeMillis())

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
