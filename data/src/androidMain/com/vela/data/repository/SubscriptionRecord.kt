package com.vela.data.repository

import com.vela.data.api.number
import com.vela.data.api.text
import com.vela.data.model.CatalogTitle
import kotlinx.serialization.json.JsonObject

data class SubscriptionRecord(
    val id: Int, val name: String, val poster: String?, val type: String?, val season: Int?,
    val catalog: CatalogTitle?, val state: String?, val added: String?, val totalEpisodes: Int?,
    val lastUpdated: String? = null, val episodeGroup: String? = null
) {
    val key: String get() = id.toString()
}

fun parseSubscription(row: JsonObject): SubscriptionRecord {
    val type = when (row.text("type")) { "电影", "movie" -> "movie"; "电视剧", "tv" -> "tv"; else -> null }
    val tmdbId = row.number("tmdbid")?.takeIf { it > 0 }
    return SubscriptionRecord(
        id = row.number("id")?.takeIf { it > 0 } ?: error("Subscription has no row identity"),
        name = row.text("name").orEmpty(), poster = row.text("poster"), type = type,
        season = if (type == "tv") row.number("season")?.takeIf { it >= 0 } else null,
        catalog = if (type != null && tmdbId != null) CatalogTitle(tmdbId, type, title = row.text("name"), posterPath = row.text("poster"), releaseDate = row.text("year")) else null,
        state = row.text("state"), added = row.text("date"), totalEpisodes = row.number("total_episode")?.takeIf { it > 0 },
        lastUpdated = row.text("last_update"), episodeGroup = row.text("episode_group")
    )
}

data class SubscriptionLibrary(
    val resources: List<FederatedMediaItem> = emptyList(), val episodes: List<FederatedMediaItem> = emptyList(),
    val errors: List<String> = emptyList(), val complete: Boolean = false
) {
    // A multi-episode file contributes each covered episode; watched on any server counts once.
    val episodeGroups: Map<Int, List<FederatedMediaItem>> get() = episodes.flatMap { row ->
        val start = row.item.indexNumber?.takeIf { it > 0 } ?: return@flatMap emptyList()
        val end = row.item.indexNumberEnd?.takeIf { it >= start && it - start < 1000 } ?: start
        (start..end).map { it to row }
    }.groupBy({ it.first }, { it.second })
    val watched: Int get() = episodeGroups.values.count { group -> group.any { it.item.userData?.played == true } }
    val pending: Map<Int, List<FederatedMediaItem>> get() = episodeGroups.filterValues { group ->
        group.none { it.item.userData?.played == true } && group.any { it.item.userData?.played == false }
    }
    val progressKnown: Boolean get() = complete && episodes.all { it.item.indexNumber?.let { number -> number > 0 } == true && it.item.userData?.played != null }
}
