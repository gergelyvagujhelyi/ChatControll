package com.chatcontroll.app.di

import com.chatcontroll.app.data.repository.ConversationRepositoryImpl
import com.chatcontroll.app.data.repository.IdentityRepositoryImpl
import com.chatcontroll.app.data.repository.MessageRepositoryImpl
import com.chatcontroll.app.data.repository.PushTokenRepositoryImpl
import com.chatcontroll.app.data.repository.SettingsRepositoryImpl
import com.chatcontroll.app.domain.repository.ConversationRepository
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.repository.MessageRepository
import com.chatcontroll.app.domain.repository.PushTokenRepository
import com.chatcontroll.app.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindIdentityRepository(impl: IdentityRepositoryImpl): IdentityRepository

    @Binds
    @Singleton
    abstract fun bindPushTokenRepository(impl: PushTokenRepositoryImpl): PushTokenRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
