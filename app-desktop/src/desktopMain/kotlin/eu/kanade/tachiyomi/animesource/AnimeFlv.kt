package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class AnimeFlv : ParsedAnimeHttpSource() {
    override val name = "AnimeFLV"
    override val baseUrl = "https://www3.animeflv.net"
    override val lang = "es"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", baseUrl)
    }

    // --- POPULAR ---
    public override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?order=popularity&page=$page", headers)

    override fun popularAnimeSelector(): String = "ul.ListAnimes li article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a") ?: throw Exception("Link not found")
        anime.setUrlWithoutDomain(linkElement.attr("href"))
        anime.title = element.selectFirst("h3.Title")?.text() ?: ""
        val imgUrl = element.selectFirst(".Image img")?.attr("src") ?: ""
        anime.thumbnail_url = if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl"
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.active + li"

    // --- LATEST / UPDATES ---
    public override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse?order=added&page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // --- SEARCH ---
    public override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/browse?q=$query&page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // --- DETAILS ---
    public override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.Title")?.text() ?: ""
        anime.description = document.selectFirst(".Description p")?.text() ?: ""
        val imgUrl = document.selectFirst(".Thumb img")?.attr("src") ?: ""
        anime.thumbnail_url = if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl"
        anime.status = parseStatus(document.selectFirst(".AnmStts")?.text() ?: "")
        anime.genre = document.select(".Nvgnrs a").joinToString { it.text() }
        return anime
    }

    private fun parseStatus(status: String): Int {
        return when {
            status.contains("En emisión", ignoreCase = true) -> SAnime.ONGOING
            status.contains("Finalizado", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // --- EPISODES LIST ---
    override fun episodeListSelector(): String = "ul.ListEpisodes li"

    public override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val html = document.html()
        val slug = response.request.url.pathSegments.last()
        val episodesPattern = Pattern.compile("var episodes = \\[(.*?)\\];")
        val matcher = episodesPattern.matcher(html)
        
        val list = mutableListOf<SEpisode>()
        if (matcher.find()) {
            val episodesData = matcher.group(1)
            val epPattern = Pattern.compile("\\[(\\d+),(\\d+)\\]")
            val epMatcher = epPattern.matcher(episodesData)
            while (epMatcher.find()) {
                val epNum = epMatcher.group(1)
                
                val episode = SEpisode.create()
                episode.url = "/ver/$slug-$epNum"
                episode.name = "Episodio $epNum"
                episode.episode_number = epNum.toFloat()
                list.add(episode)
            }
        }
        return list
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // --- VIDEO PLAYLIST ---
    public override fun videoListParse(response: Response): List<Video> {
        val html = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
        val documentHtml = if (html.isEmpty()) {
            try { response.asJsoup().html() } catch (e: Exception) { "" }
        } else {
            html
        }
        
        val videosPattern = Pattern.compile("var videos\\s*=\\s*(.*?);")
        val matcher = videosPattern.matcher(documentHtml)
        
        val list = mutableListOf<Video>()
        if (matcher.find()) {
            val jsonStr = matcher.group(1).trim()
            val serverPattern = Pattern.compile("\"server\"\\s*:\\s*\"([^\"]+)\".*?\"code\"\\s*:\\s*\"([^\"]+)\"")
            val serverMatcher = serverPattern.matcher(jsonStr)
            while (serverMatcher.find()) {
                val serverName = serverMatcher.group(1)
                val embedUrl = serverMatcher.group(2).replace("\\/", "/")
                
                list.add(Video(embedUrl, serverName, embedUrl))
            }
        }
        return list
    }

    override fun videoListSelector(): String = throw Exception("Not used")
    override fun videoFromElement(element: Element): Video = throw Exception("Not used")
    override fun videoUrlParse(document: Document): String = throw Exception("Not used")

    // --- STUBS FOR ABSTRACT MEMBERS ---
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = emptyList()
    override fun seasonListSelector(): String = ""
    override fun seasonFromElement(element: Element): SAnime = throw Exception("Not used")
    override fun hosterListSelector(): String = ""
    override fun hosterFromElement(element: Element): Hoster = throw Exception("Not used")
    override fun episodeVideoParse(response: Response): SEpisode = throw Exception("Not used")
    
    // Public visibility for parsing methods
    public override fun popularAnimeParse(response: Response): AnimesPage = super.popularAnimeParse(response)
    public override fun searchAnimeParse(response: Response): AnimesPage = super.searchAnimeParse(response)
    public override fun latestUpdatesParse(response: Response): AnimesPage = super.latestUpdatesParse(response)
}
