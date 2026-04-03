package com.chatcontroll.app.domain.usecase

import com.chatcontroll.app.domain.model.Identity
import com.chatcontroll.app.domain.repository.IdentityRepository
import javax.inject.Inject

class CreateIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(): Identity {
        return identityRepository.getOrCreateIdentity()
    }
}
