package com.ambientai.data.entities

import com.ambientai.core.media.Platform
import com.ambientai.core.media.Subscription
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class YouTubeSubscription(
    @Id override var id: Long = 0,
    @Index var channelId: String = "",
    var channelName: String = "",
    var description: String = "",
    override var thumbnailUrl: String? = null,
    override var subscribedAt: Long = System.currentTimeMillis()
) : Subscription {
    override val platform: Platform get() = Platform.YOUTUBE
    override val title: String get() = channelName
}
