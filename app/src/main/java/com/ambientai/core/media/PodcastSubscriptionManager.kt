package com.ambientai.core.media

import com.ambientai.data.entities.PodcastEpisode
import com.ambientai.data.entities.PodcastSubscription
import com.ambientai.data.repositories.IPodcastEpisodeRepository
import com.ambientai.data.repositories.IPodcastSubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastSubscriptionManager @Inject constructor(
    private val subscriptionRepository: IPodcastSubscriptionRepository,
    private val episodeRepository: IPodcastEpisodeRepository,
    private val rssFeedParser: RssFeedParser
) : BaseSubscriptionManager<PodcastSubscription>() {

    override val platform = Platform.PODCAST
    override val tag = "PodcastSubManager"

    override fun getSubscriptions(): Flow<List<PodcastSubscription>> = subscriptionRepository.getAllFlow()

    // Platform-specific: How to fetch subscription info
    override suspend fun fetchSubscriptionInfo(identifier: String): SubscriptionInfo? = withContext(Dispatchers.IO) {
        rssFeedParser.parseFeed(identifier)?.let { feed ->
            SubscriptionInfo(
                identifier = identifier,
                title = feed.show.title,
                description = feed.show.description,
                thumbnailUrl = feed.show.artworkUrl,
                metadata = mapOf(
                    "author" to feed.show.author,
                    "websiteUrl" to feed.show.websiteUrl
                )
            )
        }
    }

    // Platform-specific: How to fetch content
    override suspend fun fetchContentInfo(identifier: String): List<ContentInfo> = withContext(Dispatchers.IO) {
        rssFeedParser.parseFeed(identifier)?.episodes?.map { episode ->
            ContentInfo(
                identifier = episode.guid,
                title = episode.title,
                description = episode.description,
                thumbnailUrl = null,
                duration = episode.durationMs,
                publishDate = episode.publishDate,
                metadata = mapOf(
                    "audioUrl" to episode.audioUrl,
                    "episodeNumber" to episode.episodeNumber,
                    "chaptersUrl" to episode.chaptersUrl
                )
            )
        } ?: emptyList()
    }

    // Platform-specific: Entity creation
    override fun createAndSaveSubscription(info: SubscriptionInfo) = subscriptionRepository.save(
        PodcastSubscription(
            feedUrl = info.identifier,
            title = info.title,
            author = info.metadata["author"] as? String ?: "",
            description = info.description,
            artworkUrl = info.thumbnailUrl,
            websiteUrl = info.metadata["websiteUrl"] as? String,
            lastPolledAt = System.currentTimeMillis()
        )
    )

    override fun saveNewContent(subscription: PodcastSubscription, content: List<ContentInfo>): Int {
        val existingGuids = episodeRepository.getBySubscription(subscription.id).map { it.guid }.toSet()
        return content.filterNot { it.identifier in existingGuids }.map { item ->
            episodeRepository.save(PodcastEpisode(
                subscriptionId = subscription.id,
                guid = item.identifier,
                title = item.title,
                description = item.description,
                audioUrl = item.metadata["audioUrl"] as? String ?: "",
                duration = item.duration,
                publishDate = item.publishDate,
                episodeNumber = (item.metadata["episodeNumber"] as? Number)?.toInt() ?: 0,
                chaptersUrl = item.metadata["chaptersUrl"] as? String,
                artworkUrl = subscription.artworkUrl
            ))
        }.size
    }

    override fun deleteContent(subscriptionId: Long) = episodeRepository.deleteBySubscription(subscriptionId)
    override fun deleteSubscription(subscriptionId: Long) = subscriptionRepository.delete(subscriptionId)

    // Platform-specific: Repository access
    override fun getByIdentifier(identifier: String) = subscriptionRepository.getByFeedUrl(identifier)
    override fun getAllSubscriptionsList() = subscriptionRepository.getAll()
    override fun getSubscriptionById(id: Long) = subscriptionRepository.getById(id)
    override fun getSubscriptionIdentifier(subscription: PodcastSubscription) = subscription.feedUrl

    override fun onRefreshComplete(subscription: PodcastSubscription) {
        subscriptionRepository.updateLastPolled(subscription.id, System.currentTimeMillis())
    }
}
