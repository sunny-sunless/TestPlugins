package com.reanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Re:ANIME provider.
 *
 * Site pages are server-rendered, but the player may use a JavaScript-selected
 * server. The resolver deliberately delegates embed URLs to CloudStream's
 * extractor registry instead of treating an iframe page as a playable file.
 */
class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.cz"
    override var name = "Re:ANIME"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)
    override val mainPage = mainPageOf(
        mainPage("$mainUrl/home", "Latest"),
        mainPage("$mainUrl/home?genres=Trending", "Trending")
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private fun Element.firstImage(): String? {
        val image = selectFirst("img")
        return image?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank { it.attr("src") }
            }
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun Element.cardTitle(): String =
        selectFirst("h2, h3, .title, [title]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: attr("title").ifBlank { text().trim().lineSequence().firstOrNull().orEmpty() }

    private fun animeCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = hashSetOf<String>()
        return document.select("a[href*=/anime/]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            if (href.isBlank() || !seen.add(href)) return@mapNotNull null
            val title = anchor.cardTitle()
            if (title.isBlank()) return@mapNotNull null
            val poster = anchor.firstImage()
            @Suppress("DEPRECATION_ERROR")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                name,
                TvType.Anime,
                poster
            )
        }
    }

    private suspend fun searchDocuments(query: String, page: Int): List<org.jsoup.nodes.Document> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val candidates = listOf(
            "$mainUrl/search?keyword=$encoded&page=$page",
            "$mainUrl/search?q=$encoded&page=$page",
            "$mainUrl/home?search=$encoded&page=$page"
        )
        return candidates.mapNotNull { url ->
            runCatching { app.get(url, headers = headers).document }.getOrNull()
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val docs = searchDocuments(query, page)
        val results = docs.asSequence().flatMap { animeCards(it).asSequence() }
            .distinctBy { it.url }
            .toList()
        return newSearchResponseList(results, results.isNotEmpty() && page < 5)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.let {
            if (it.contains("?")) "$it&page=$page" else "$it?page=$page"
        }
        val document = app.get(url, headers = headers).document
        return newHomePageResponse(request, animeCards(document), animeCards(document).isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim().ifBlank { "Re:ANIME" }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: document.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("[class*=description], [class*=synopsis], [class*=plot]")?.text()
        val pageText = document.text()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(pageText)?.value?.toIntOrNull()
        val tags = document.select("a[href*='genre'], a[href*='genres']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val episodeLinks = document.select("a[href*=/watch/]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val label = anchor.text().trim().ifBlank { anchor.attr("title") }
            val ep = Regex("(?:ep=|episode[-_ ]?)(\\d+)", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("\\b(\\d{1,4})\\b").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val isDub = Regex("\\bdub\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
                Regex("lang=dub", RegexOption.IGNORE_CASE).containsMatchIn(href)
            Triple(fixUrl(href), label.ifBlank { "Episode ${ep ?: ""}" }, Triple(ep, isDub, anchor.selectFirst("img")?.attr("src")))
        }.distinctBy { it.first }

        val subEpisodes = episodeLinks.filterNot { it.third.second }.map { item ->
            newEpisode(item.first) {
                name = item.second
                episode = item.third.first
                posterUrl = item.third.third?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }
        }
        val dubEpisodes = episodeLinks.filter { it.third.second }.map { item ->
            newEpisode(item.first) {
                name = item.second
                episode = item.third.first
                posterUrl = item.third.third?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime, comingSoonIfNone = false) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            if (subEpisodes.isNotEmpty()) episodes[DubStatus.Subbed] = subEpisodes
            if (dubEpisodes.isNotEmpty()) episodes[DubStatus.Dubbed] = dubEpisodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
        val document = app.get(watchUrl, headers = headers).document
        var found = false

        // Re:ANIME exposes SUB/DUB and server buttons on the watch page.
        // Prefer the selected iframe, then inspect alternate server controls.
        val embedUrls = linkedSetOf<String>()
        document.select("iframe[src], iframe[data-src], [data-src*='http'], a[href*='embed']").forEach { element ->
            val candidate = element.attr("src").ifBlank {
                element.attr("data-src").ifBlank { element.attr("href") }
            }
            if (candidate.startsWith("http")) embedUrls.add(fixUrl(candidate))
        }

        // Some server controls keep their embed URL in data attributes or inline onclick.
        document.select("[data-src], [data-url], [data-embed], [onclick]").forEach { element ->
            val raw = listOf("data-src", "data-url", "data-embed", "onclick")
                .map { element.attr(it) }.firstOrNull { it.contains("http") }.orEmpty()
            Regex("""https?://[^"'\s<>]+""").find(raw)?.value?.let {
                embedUrls.add(it.replace("&amp;", "&"))
            }
        }

        document.select("track[src], track[data-src], a[href$=.vtt], a[href$=.srt]").forEach { track ->
            val subtitle = track.attr("src").ifBlank { track.attr("data-src").ifBlank { track.attr("href") } }
            if (subtitle.isNotBlank()) {
                val lang = track.attr("srclang").ifBlank { track.attr("label").ifBlank { "Unknown" } }
                subtitleCallback(SubtitleFile(lang, fixUrl(subtitle)))
            }
        }

        for (embed in embedUrls) {
            if (embed.contains(".m3u8") || embed.contains(".mp4") || embed.contains(".mpd")) {
                callback(
                    newExtractorLink(name, "Re:ANIME", embed) {
                        referer = watchUrl
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            } else {
                runCatching {
                    loadExtractor(embed, watchUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }
        return found
    }
}
