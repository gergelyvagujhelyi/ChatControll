package com.chatcontroll.app.domain.usecase

import com.chatcontroll.app.domain.model.Contact
import com.chatcontroll.app.domain.repository.IdentityRepository
import javax.inject.Inject

class AddContactUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(shareCode: String): Result<Contact> = runCatching {
        require(shareCode.isNotBlank()) { "Share code cannot be blank" }
        identityRepository.addContact(shareCode.trim())
    }
}
