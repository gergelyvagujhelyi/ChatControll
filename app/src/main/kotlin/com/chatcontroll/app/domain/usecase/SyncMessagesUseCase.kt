package com.chatcontroll.app.domain.usecase

import com.chatcontroll.app.domain.repository.MessageRepository
import javax.inject.Inject

class SyncMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
) {
    suspend operator fun invoke(): Result<Unit> = runCatching {
        messageRepository.fetchPendingFromServer()
    }
}
