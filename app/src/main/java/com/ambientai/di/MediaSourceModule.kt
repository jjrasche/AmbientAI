package com.ambientai.di

import com.ambientai.core.media.LocalAudioSource
import com.ambientai.core.media.MediaSource
import com.ambientai.core.media.PodcastSource
import com.ambientai.core.media.YouTubeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaSourceModule {
    @Binds
    @IntoSet
    abstract fun bindLocalAudioSource(source: LocalAudioSource): MediaSource

    @Binds
    @IntoSet
    abstract fun bindYouTubeSource(source: YouTubeSource): MediaSource

    @Binds
    @IntoSet
    abstract fun bindPodcastSource(source: PodcastSource): MediaSource
}
