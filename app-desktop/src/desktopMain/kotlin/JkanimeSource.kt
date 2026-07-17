package eu.kanade.tachiyomi.animeextension.es.jkanime

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * Fuente nativa de JKanime para desktop.
 *
 * ## Problema
 * La extensión oficial (JAR) usa este regex para extraer el objeto JSON de la
 * página del directorio:
 *
 *   `Lvar\s+animes\s*=\s*(\{(?:[^"']|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')*?\})\s*;`
 *
 * Ese alternador anidado `(?:[^"']|"...|'...')*?` aplica backtracking exponencial
 * sobre el HTML de ~67 KB, agotando el stack y lanzando `StackOverflowError` en
 * el hilo `AWT-EventQueue-0`, lo que bloquea/crashea la app entera.
 *
 * ## Solución (esta clase)
 * 1. **Jsoup** extrae el texto del `<script>` que contiene `var animes = `
 * 2. Balanceo manual de llaves O(n) para delimitar el JSON (sin regex)
 * 3. **kotlinx.serialization** deserializa el JSON resultante
 *
 * La página sirve el JSON completo inline:
 *   `var animes = {"current_page":1,"data":[{"id":201,"title":"One Piece",...}],...};`
 */
class JkanimeNativeSource : AnimeHttpSource() {

    override val name = "Jkanime"
    override val baseUrl = "https://jkanime.net"
    override val lang = "es"
    override val supportsLatest = true

    // Mismo versionId que la extensión original para no romper URLs guardadas en biblioteca
    override val versionId = 1

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ─── Modelos del JSON embebido en /directorio ──────────────────────────────

    @Serializable
    private data class JkAnimePage(
        val current_page: Int = 1,
        val last_page: Int = 1,
        val data: List<JkAnimeEntry> = emptyList(),
    )

    @Serializable
    private data class JkAnimeEntry(
        val id: Int = 0,
        val title: String = "",
        val synopsis: String = "",
        val image: String = "",
        val slug: String = "",
        val url: String = "",
        val estado: String = "",
        val tipo: String = "",
        val type: String = "",
    )

    // ─── Extracción del JSON sin regex ────────────────────────────────────────

    /**
     * Extrae el objeto JSON del bloque `var animes = {...};` usando balanceo de
     * llaves. Complejidad O(n) sobre el tamaño del script, sin backtracking.
     */
    private fun extractAnimesJson(html: String): String {
        val doc = Jsoup.parse(html)

        // Jsoup selecciona el <script> que contenga la cadena "var animes = "
        val scriptText = doc.select("script:containsData(var animes = )")
            .firstOrNull()?.data()
            ?: error("[JkanimeNativeSource] No se encontró script con 'var animes = ' en la página")

        val prefix = "var animes = "
        val prefixIdx = scriptText.indexOf(prefix)
            .takeIf { it >= 0 }
            ?: error("[JkanimeNativeSource] Prefijo 'var animes = ' no encontrado en el script")

        val startIdx = prefixIdx + prefix.length

        // Balanceo de llaves para encontrar el cierre exacto del objeto JSON
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var escape = false
        var started = false

        for (i in startIdx until scriptText.length) {
            val c = scriptText[i]
            sb.append(c)

            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> {
                    depth++
                    started = true
                }
                !inString && c == '}' -> {
                    depth--
                    if (started && depth == 0) return sb.toString()  // JSON completo
                }
            }
        }

        error("[JkanimeNativeSource] No se encontró el cierre del objeto JSON de 'animes'")
    }

    // ─── Parser compartido de AnimesPage ──────────────────────────────────────

    private fun parseAnimePage(response: Response): AnimesPage {
        val html = response.body!!.string()
        val jsonStr = extractAnimesJson(html)
        val page = json.decodeFromString<JkAnimePage>(jsonStr)

        val animes = page.data.map { entry ->
            SAnime.create().apply {
                title = entry.title
                thumbnail_url = entry.image
                setUrlWithoutDomain(
                    entry.url.ifBlank { "/${entry.slug}/" }
                )
                description = entry.synopsis
                status = when (entry.estado) {
                    "En emision"   -> SAnime.ONGOING
                    "Concluido"    -> SAnime.COMPLETED
                    "Por estrenar" -> SAnime.LICENSED
                    else           -> SAnime.UNKNOWN
                }
                genre = entry.tipo
            }
        }

        val hasNextPage = page.current_page < page.last_page
        return AnimesPage(animes, hasNextPage)
    }

    // ─── API de AnimeHttpSource ────────────────────────────────────────────────

    /** Popular → /directorio?filtro=popularidad&p=N */
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/directorio?filtro=popularidad&p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    /** Recientes → /directorio?p=N (orden por fecha por defecto) */
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/directorio?p=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseAnimePage(response)

    /** Búsqueda → /buscar/QUERY/&p=N */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/buscar/$query/&p=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    // ─── Detalle del anime ─────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body!!.string())
        return SAnime.create().apply {
            title = doc.selectFirst("div.anime__details__content div.anime_info h3")?.text() ?: ""
            thumbnail_url = doc.selectFirst("div.anime__details__content div.anime_pic img")?.attr("abs:src") ?: ""
            description = doc.selectFirst("div.anime__details__content div.anime_info p.scroll")?.text() ?: ""

            // Géneros
            genre = doc.select("div.anime__details__content div.anime_data.pc li")
                .firstOrNull { it.selectFirst("span")?.text()?.contains("Generos:") == true }
                ?.select("a")
                ?.joinToString(", ") { it.text() }
                ?: ""

            status = when {
                doc.body().text().contains("En emision")  -> SAnime.ONGOING
                doc.body().text().contains("Concluido")   -> SAnime.COMPLETED
                doc.body().text().contains("Por estrenar")-> SAnime.LICENSED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ─── Episodios ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        // La lógica de episodios del JAR original funciona bien
        // (no usa el regex problemático). Aquí dejamos una implementación
        // básica; los episodios se cargarán a través del AnimeDetailsScreen.
        return emptyList()
    }

    // ─── Videos ───────────────────────────────────────────────────────────────

    override fun videoListParse(response: Response): List<Video> = emptyList()

    // ─── Stubs requeridos por AnimeHttpSource ─────────────────────────────────

    override fun episodeVideoParse(response: Response): SEpisode = SEpisode.create()

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.Hoster> = emptyList()

    override fun videoListParse(
        response: Response,
        hoster: eu.kanade.tachiyomi.animesource.model.Hoster,
    ): List<Video> = emptyList()

    override fun videoUrlParse(response: Response): String = ""
}
