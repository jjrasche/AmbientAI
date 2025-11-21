package com.ambientai.core.media

interface Subscription {
    val id: Long
    val title: String
    val platform: Platform
    val subscribedAt: Long
    val thumbnailUrl: String?
}
