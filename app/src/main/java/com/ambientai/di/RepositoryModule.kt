package com.ambientai.di

import com.ambientai.core.media.MediaLibrary
import com.ambientai.core.media.MediaLibraryImpl
import com.ambientai.data.repositories.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds repository interfaces to their implementations.
 *
 * This uses @Binds which is more efficient than @Provides for simple interface -> implementation mappings.
 *
 * WHY THIS MATTERS:
 * - Services/ViewModels depend on ITranscriptRepository (interface)
 * - Hilt knows to inject TranscriptRepository (implementation)
 * - Tests can provide FakeTranscriptRepository instead
 * - Swapping implementations doesn't require changing injection sites
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTranscriptRepository(impl: TranscriptRepository): ITranscriptRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepository): ITaskRepository

    @Binds
    @Singleton
    abstract fun bindActionExecutionRepository(impl: ActionExecutionRepository): IActionExecutionRepository

    @Binds
    @Singleton
    abstract fun bindWorkflowDefinitionRepository(impl: WorkflowDefinitionRepository): IWorkflowDefinitionRepository

    @Binds
    @Singleton
    abstract fun bindWorkflowExecutionRepository(impl: WorkflowExecutionRepository): IWorkflowExecutionRepository

    @Binds
    @Singleton
    abstract fun bindLogEntryRepository(impl: LogEntryRepository): ILogEntryRepository

    @Binds
    @Singleton
    abstract fun bindNarrativeRepository(impl: NarrativeRepository): INarrativeRepository

    @Binds
    @Singleton
    abstract fun bindMediaHistoryRepository(impl: MediaHistoryRepository): IMediaHistoryRepository

    @Binds
    @Singleton
    abstract fun bindGoldenDatasetRepository(impl: GoldenDatasetRepository): IGoldenDatasetRepository

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepository): IMediaRepository

    @Binds
    @Singleton
    abstract fun bindMediaTranscriptRepository(impl: MediaTranscriptRepository): IMediaTranscriptRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptSegmentRepository(impl: TranscriptSegmentRepository): ITranscriptSegmentRepository

    @Binds
    @Singleton
    abstract fun bindMediaNoteRepository(impl: MediaNoteRepository): IMediaNoteRepository

    @Binds
    @Singleton
    abstract fun bindMediaPlaybackSessionRepository(impl: MediaPlaybackSessionRepository): IMediaPlaybackSessionRepository

    @Binds
    @Singleton
    abstract fun bindPodcastSubscriptionRepository(impl: PodcastSubscriptionRepository): IPodcastSubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindPodcastEpisodeRepository(impl: PodcastEpisodeRepository): IPodcastEpisodeRepository

    @Binds
    @Singleton
    abstract fun bindYouTubeVideoRepository(impl: YouTubeVideoRepository): IYouTubeVideoRepository

    @Binds
    @Singleton
    abstract fun bindYouTubeSubscriptionRepository(impl: YouTubeSubscriptionRepository): IYouTubeSubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindMediaLibrary(impl: MediaLibraryImpl): MediaLibrary
}
