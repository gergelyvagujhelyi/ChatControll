package com.chatcontroll.app.domain

import com.chatcontroll.app.domain.model.Message
import com.chatcontroll.app.domain.model.MessageState
import com.chatcontroll.app.domain.repository.MessageRepository
import com.chatcontroll.app.domain.usecase.SendMessageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageUseCaseTest {

    private val messageRepository: MessageRepository = mockk()
    private val useCase = SendMessageUseCase(messageRepository)

    @Test
    fun `send message calls repository with correct params`() = runTest {
        val message = Message(
            id = "1",
            conversationId = "conv1",
            senderId = "me",
            recipientId = "them",
            plaintext = "Hello",
            state = MessageState.SENT,
            timestamp = Clock.System.now(),
            isOutgoing = true,
        )

        coEvery {
            messageRepository.sendMessage("conv1", "them", "Hello")
        } returns message

        val result = useCase("conv1", "them", "Hello")

        assertTrue(result.isSuccess)
        coVerify { messageRepository.sendMessage("conv1", "them", "Hello") }
    }

    @Test
    fun `blank message returns failure`() = runTest {
        val result = useCase("conv1", "them", "   ")
        assertTrue(result.isFailure)
    }

    @Test
    fun `empty message returns failure`() = runTest {
        val result = useCase("conv1", "them", "")
        assertTrue(result.isFailure)
    }

    @Test
    fun `message exceeding max length returns failure`() = runTest {
        val longMessage = "a".repeat(SendMessageUseCase.MAX_MESSAGE_LENGTH + 1)
        val result = useCase("conv1", "them", longMessage)
        assertTrue(result.isFailure)
    }

    @Test
    fun `repository exception propagates as failure`() = runTest {
        coEvery {
            messageRepository.sendMessage(any(), any(), any())
        } throws RuntimeException("Network error")

        val result = useCase("conv1", "them", "Hello")
        assertTrue(result.isFailure)
    }
}
