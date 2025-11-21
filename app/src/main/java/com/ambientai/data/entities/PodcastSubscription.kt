package com.ambientai.data.entities

import com.ambientai.core.media.Platform
import com.ambientai.core.media.Subscription
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class PodcastSubscription(
    @Id override var id: Long = 0,
    @Index var feedUrl: String = "",
    var podcastIndexId: Long? = null,
    override var title: String = "",
    var author: String = "",
    var description: String = "",
    var artworkUrl: String? = null,
    var websiteUrl: String? = null,
    var lastPolledAt: Long = 0,
    var autoDownload: Boolean = true,
    override var subscribedAt: Long = System.currentTimeMillis()
) : Subscription {
    override val platform: Platform get() = Platform.PODCAST
    override val thumbnailUrl: String? get() = artworkUrl
}
