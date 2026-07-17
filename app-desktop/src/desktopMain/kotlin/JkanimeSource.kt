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
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.Base64

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

    // ─── Modelos de episodios (API AJAX) ──────────────────────────────────────

    /** Respuesta de POST /ajax/episodes/{id}/{pag} */
    @Serializable
    private data class JkEpisodesResponse(
        val total: Int = 0,
        val data: List<JkEpisodeEntry> = emptyList(),
    )

    @Serializable
    private data class JkEpisodeEntry(
        val id: Int = 0,
        val number: Int = 0,
        val image: String = "",
    )

    // ─── Modelos de servidores externos (array `servers` en el HTML del episodio) ───

    @Serializable
    private data class JkServer(
        val remote: String = "",   // URL en base64
        val server: String = "",   // Nombre: Streamwish, Mega, VOE...
        val lang: Int = 1,
        val size: String = "",
        val append: Int = 0,
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

    /**
     * El primer request carga la página del anime para obtener:
     *  - el CSRF token (`<meta name="csrf-token">`)
     *  - el anime ID (`data-anime="4784"` en #guardar-anime)
     * Con esos datos hacemos POST paginados a /ajax/episodes/{id}/{pag}
     */
    override fun episodeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)

        // Obtener CSRF token
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        // Obtener el ID interno del anime desde data-anime
        val animeId = doc.selectFirst("div#guardar-anime[data-anime]")?.attr("data-anime")
            ?: return emptyList()

        // Construir la URL base del anime para los links de episodios
        val animeSlug = response.request.url.encodedPath.trimEnd('/')

        // Paginar: el sitio sirve 16 eps/página
        val episodesPerPage = 16
        val allEpisodes = mutableListOf<JkEpisodeEntry>()
        var page = 1

        while (true) {
            val body = FormBody.Builder()
                .add("_token", csrfToken)
                .build()
            val req = okhttp3.Request.Builder()
                .url("$baseUrl/ajax/episodes/$animeId/$page")
                .post(body)
                .headers(headers)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) break

            val result = runCatching {
                json.decodeFromString<JkEpisodesResponse>(resp.body!!.string())
            }.getOrNull() ?: break

            allEpisodes.addAll(result.data)

            val totalPages = Math.ceil(result.total.toDouble() / episodesPerPage).toInt()
            if (page >= totalPages || result.data.isEmpty()) break
            page++
        }

        // Construir lista de SEpisode ordenada de más reciente a más antigua
        return allEpisodes
            .sortedByDescending { it.number }
            .map { entry ->
                SEpisode.create().apply {
                    name = "Episodio ${entry.number}"
                    episode_number = entry.number.toFloat()
                    setUrlWithoutDomain("$animeSlug/${entry.number}/")
                }
            }
    }

    // ─── Videos ─────────────────────────────────────────────────────────────

    /**
     * Parsea el HTML del episodio y extrae todos los servidores de video.
     *
     * El HTML del episodio contiene DOS fuentes de servers:
     *
     * 1) Array `video[]` en JS — iframes directos para Desu/Magi (reproductores propios):
     *    ```js
     *    video[0] = '<iframe src="https://jkanime.net/jkplayer/um?e=...&t=HASH&op=...">...';
     *    ```
     *    Se extraen con regex simple sobre el texto del script (son pocas las entradas).
     *
     * 2) Array `servers` en JS — servidores externos (Streamwish, VOE, Mega, Mixdrop, etc.):
     *    ```js
     *    var servers = [{"remote":"BASE64_URL","server":"Streamwish",...}, ...]
     *    ```
     *    Se extraen con kotlinx.serialization. El `remote` es la URL en Base64.
     *    El iframe resultante es: /jkplayer/c1?u={remote}&s={server_name}
     */
    override fun videoListParse(response: Response): List<Video> {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)
        val videos = mutableListOf<Video>()

        // ─── 1. Servidores propios del script 'CARGAR REPRODUCTORES AISLADOS' ───
        // Busca el script que contiene el array video[] con iframes
        val scriptWithVideos = doc.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("video[0]") && it.contains("player_conte") }

        if (scriptWithVideos != null) {
            // Extrae cada video[N] = '...src="URL"...'
            val iframeSrcRegex = Regex("""src=\"(https://jkanime\.net/jkplayer/[^\"]+)""")
            // Obtiene el nombre del servidor desde .bg-servers a[data-id=N]
            val serverBtns = doc.select("div.bg-servers a.btn-show")
            val btnById = serverBtns.associateBy { it.attr("data-id") }

            val videoEntries = Regex("""video\[(\d+)\]\s*=\s*'(.*?)'(?=;)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(scriptWithVideos)
            for (m in videoEntries) {
                val idx = m.groupValues[1]
                val iframeHtml = m.groupValues[2]
                val src = iframeSrcRegex.find(iframeHtml)?.groupValues?.get(1) ?: continue
                val serverName = btnById[idx]?.text() ?: "Servidor $idx"

                // Resolver el stream real del reproductor jkplayer
                val streamUrl = extractStreamFromJkplayer(src)
                if (streamUrl != null) {
                    videos.add(Video(streamUrl, "[JK-Propio] $serverName", streamUrl))
                } else {
                    // Fallback: usar la URL del iframe si no se pudo extraer el stream
                    videos.add(Video(src, "[JK-Propio] $serverName", src))
                }
            }
        }

        // ─── 2. Servidores externos del array `servers` (base64) ───
        // NOTA: Los servidores externos (Mega, Streamwish, VOE, etc.) requieren JavaScript
        // para extraer el stream real. El framework desktop no puede ejecutar JS.
        // Por ahora, solo usamos los servidores propios de JKanime (Desu, Magi, Xtreme S)
        // que ya tienen streams directos del script video[] arriba.
        /*
        val scriptWithServers = doc.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("var servers =") }

        if (scriptWithServers != null) {
            val serversPrefix = "var servers = "
            val idx = scriptWithServers.indexOf(serversPrefix)
            if (idx >= 0) {
                // Extraer array JSON con balanceo de corchetes
                val startIdx = idx + serversPrefix.length
                val sb = StringBuilder()
                var depth = 0
                var inString = false
                var escape = false

                for (i in startIdx until scriptWithServers.length) {
                    val c = scriptWithServers[i]
                    sb.append(c)
                    when {
                        escape -> escape = false
                        c == '\\' && inString -> escape = true
                        c == '"' -> inString = !inString
                        !inString && (c == '[' || c == '{') -> depth++
                        !inString && (c == ']' || c == '}') -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                }

                val serversJson = sb.toString()
                val servers = runCatching {
                    json.decodeFromString<List<JkServer>>(serversJson)
                }.getOrElse { emptyList() }

                for (srv in servers) {
                    if (srv.server.equals("Mediafire", ignoreCase = true)) continue // no embeddable
                    val remoteUrl = runCatching {
                        String(java.util.Base64.getDecoder().decode(srv.remote))
                    }.getOrNull() ?: continue

                    // Devolver la URL decodificada directamente (embed externo)
                    // El framework desktop manejará estos embeds
                    videos.add(Video(remoteUrl, "[${srv.server}] ${srv.size}", remoteUrl))
                }
            }
        }
        */

        return videos
    }

    // ─── Extracción de stream del reproductor jkplayer ─────────────────────────────

    /**
     * Hace request al reproductor jkplayer y extrae la URL del stream real.
     */
    private fun extractStreamFromJkplayer(playerUrl: String): String? {
        return runCatching {
            val req = GET(playerUrl, headers)
            val resp = client.newCall(req).execute()
            val html = resp.body?.string() ?: return@runCatching null

            val doc = Jsoup.parse(html)

            // Buscar video directo
            val videoElement = doc.selectFirst("video")
            if (videoElement != null) {
                val src = videoElement.attr("src")
                if (src.isNotBlank()) {
                    return@runCatching if (src.startsWith("http")) src else "$baseUrl$src".replace("/+", "/")
                }
            }

            // Buscar source dentro de video
            val sourceElement = doc.selectFirst("video source")
            if (sourceElement != null) {
                val src = sourceElement.attr("src")
                if (src.isNotBlank()) {
                    return@runCatching if (src.startsWith("http")) src else "$baseUrl$src".replace("/+", "/")
                }
            }

            // Buscar URL de video en scripts del reproductor
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptText = script.data()
                // Buscar patrones comunes de URLs de video en jkplayer
                val urlPattern = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                val match = urlPattern.find(scriptText)
                if (match != null) {
                    return@runCatching match.value
                }

                // Buscar URLs .mp4
                val mp4Pattern = Regex("""https?://[^\s"']+\.mp4[^\s"']*""")
                val mp4Match = mp4Pattern.find(scriptText)
                if (mp4Match != null) {
                    return@runCatching mp4Match.value
                }
            }

            null
        }.getOrNull()
    }

    // Stub: métodos requeridos por AnimeHttpSource
    override fun videoListParse(response: Response, hoster: eu.kanade.tachiyomi.animesource.model.Hoster): List<Video> = emptyList()

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.Hoster> = emptyList()

    override fun episodeVideoParse(response: Response): SEpisode = throw Exception("Not used")

    override fun videoUrlParse(response: Response): String = ""

    // ─── Resolución de videos ─────────────────────────────────────────────────────

    /**
     * Resuelve la URL real del video desde un embed de jkplayer.
     * Este método es llamado por el framework para obtener el stream real.
     */
    override suspend fun resolveVideo(video: Video): Video? {
        val url = video.videoUrl
        if (url.isEmpty()) return null

        // Si ya es una URL directa (no es un reproductor jk), devolver tal cual
        if (!url.contains("jkanime.net/jkplayer")) {
            return video
        }

        // Resolver los reproductores propios de JKanime (um, umv, jk)
        return runCatching {
            val req = GET(url, headers)
            val resp = client.newCall(req).execute()
            val html = resp.body?.string() ?: return@runCatching video

            val doc = Jsoup.parse(html)

            // Buscar video directo en el reproductor
            val videoElement = doc.selectFirst("video")
            if (videoElement != null) {
                val src = videoElement.attr("src")
                if (src.isNotBlank()) {
                    return@runCatching Video(
                        if (src.startsWith("http")) src else "$baseUrl$src".replace("/+", "/"),
                        video.videoTitle,
                        src
                    )
                }
            }

            // Buscar source dentro de video
            val sourceElement = doc.selectFirst("video source")
            if (sourceElement != null) {
                val src = sourceElement.attr("src")
                if (src.isNotBlank()) {
                    return@runCatching Video(
                        if (src.startsWith("http")) src else "$baseUrl$src".replace("/+", "/"),
                        video.videoTitle,
                        src
                    )
                }
            }

            // Buscar URL de video en scripts del reproductor
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptText = script.data()
                // Buscar patrones comunes de URLs de video en jkplayer
                val urlPattern = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                val match = urlPattern.find(scriptText)
                if (match != null) {
                    return@runCatching Video(
                        match.value,
                        video.videoTitle,
                        match.value
                    )
                }

                // Buscar URLs .mp4
                val mp4Pattern = Regex("""https?://[^\s"']+\.mp4[^\s"']*""")
                val mp4Match = mp4Pattern.find(scriptText)
                if (mp4Match != null) {
                    return@runCatching Video(
                        mp4Match.value,
                        video.videoTitle,
                        mp4Match.value
                    )
                }
            }

            // Si no se encontró nada, devolver el video original
            video
        }.getOrNull() ?: video
    }
}
