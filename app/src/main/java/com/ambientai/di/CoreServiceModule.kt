package com.ambientai.di

import android.content.Context
import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.log.LogManager
import com.ambientai.core.search.SearchService
import com.ambientai.core.task.TaskManager
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.workflow.WorkflowRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module that provides all core services.
 *
 * This module centralizes the provision of core services like:
 * - LLM services (GroqLlmService)
 * - Search services (SearchService)
 * - Task management (TaskManager)
 * - Logging (LogManager)
 * - Workflow routing (WorkflowRouter)
 * - Text-to-speech (TextToSpeechService)
 *
 * WHY THIS MATTERS:
 * - Services are now injectable instead of manually created
 * - Tests can provide fake implementations
 * - Dependencies are managed by Hilt, not manual instantiation
 * - Easier to swap implementations without changing injection sites
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreServiceModule {

    /**
     * Provides a singleton TextToSpeechService instance.
     *
     * Note: This provides a default instance without an error callback.
     * Services that need custom error handling should create their own instance.
     */
    @Provides
    @Singleton
    fun provideTextToSpeechService(@ApplicationContext context: Context): TextToSpeechService =
        TextToSpeechService(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
