package eu.kanade.tachiyomi.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves a Cloudflare challenge in an installed Chromium browser. A fresh,
 * isolated browser profile is used so the app never reads the user's normal
 * browser cookies. The user completes the challenge manually; the resulting
 * cookies and browser-bound request headers are then copied to OkHttp.
 */
object DesktopCloudflareWebViewHandler : DesktopCloudflareHandler {
    private const val CHALLENGE_TIMEOUT_SECONDS = 180L
    private const val DEVTOOLS_START_TIMEOUT_SECONDS = 15L
    private const val CLEARANCE_COOKIE = "cf_clearance"

    private val json = Json { ignoreUnknownKeys = true }
    private val localHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /** Prevent overlapping browser profiles when several requests fail together. */
    @Synchronized
    override fun resolve(
        url: HttpUrl,
        headers: Headers,
        oldCookies: List<Cookie>,
    ): DesktopCloudflareResult? {
        val browser = findChromiumBrowser()
        if (browser == null) {
            println("[CloudflareDesktop] Chrome or Edge was not found")
            return null
        }

        val rejectedClearance = oldCookies
            .firstOrNull { it.name == CLEARANCE_COOKIE }
            ?.value
        DesktopCookieJar.shared.remove(url, setOf(CLEARANCE_COOKIE))

        val profileDirectory = Files.createTempDirectory("aniyomi-cloudflare-")
        var browserProcess: Process? = null
        var devTools: DevToolsSession? = null

        try {
            val port = findAvailableLoopbackPort()
            browserProcess = ProcessBuilder(
                browser.executable.toString(),
                "--user-data-dir=${profileDirectory.toAbsolutePath()}",
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=$port",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-mode",
                "--disable-sync",
                "--new-window",
                "--window-size=1100,760",
                "about:blank",
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            val webSocketUri = waitForPageTarget(port)
            devTools = DevToolsSession.connect(webSocketUri)
            devTools.command("Network.enable")
            devTools.command("Page.enable")
            seedBrowserCookies(devTools, url, oldCookies)
            devTools.command(
                "Page.navigate",
                buildJsonObject { put("url", url.toString()) },
            )
            println(
                "[CloudflareDesktop] Opened ${browser.name} with an isolated profile for ${url.host}",
            )

            val deadline = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(CHALLENGE_TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                val browserCookies = readBrowserCookies(devTools, url)
                val clearance = browserCookies.firstOrNull { it.name == CLEARANCE_COOKIE }
                if (
                    clearance != null &&
                    clearance.value.isNotEmpty() &&
                    clearance.value != rejectedClearance
                ) {
                    val requestHeaders = readBrowserRequestHeaders(devTools, headers)
                    println(
                        "[CloudflareDesktop] Captured ${browserCookies.size} cookies " +
                            "(${browserCookies.joinToString { it.name }}) for ${url.host}",
                    )
                    return DesktopCloudflareResult(
                        cookies = browserCookies,
                        requestHeaders = requestHeaders,
                    )
                }
                Thread.sleep(500)
            }

            println("[CloudflareDesktop] Chromium verification timed out for ${url.host}")
            return null
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (error: Throwable) {
            println("[CloudflareDesktop] Chromium verification failed: ${error.message}")
            return null
        } finally {
            val childProcesses = runCatching {
                browserProcess?.toHandle()?.descendants()?.toList().orEmpty()
            }.getOrDefault(emptyList())
            runCatching { devTools?.command("Browser.close", timeoutSeconds = 2) }
            runCatching { devTools?.close() }
            browserProcess?.let { process ->
                if (process.isAlive) {
                    process.destroy()
                    runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            childProcesses.forEach { child ->
                if (child.isAlive) child.destroy()
            }
            deleteTemporaryProfile(profileDirectory)
        }
    }

    private fun findChromiumBrowser(): ChromiumBrowser? {
        val programFiles = System.getenv("ProgramFiles")
        val programFilesX86 = System.getenv("ProgramFiles(x86)")
        val localAppData = System.getenv("LOCALAPPDATA")
        val candidates = buildList {
            addBrowser("Google Chrome", localAppData, "Google", "Chrome", "Application", "chrome.exe")
            addBrowser("Google Chrome", programFiles, "Google", "Chrome", "Application", "chrome.exe")
            addBrowser("Google Chrome", programFilesX86, "Google", "Chrome", "Application", "chrome.exe")
            addBrowser("Microsoft Edge", programFilesX86, "Microsoft", "Edge", "Application", "msedge.exe")
            addBrowser("Microsoft Edge", programFiles, "Microsoft", "Edge", "Application", "msedge.exe")
            addBrowser("Microsoft Edge", localAppData, "Microsoft", "Edge", "Application", "msedge.exe")
        }
        return candidates.firstOrNull { Files.isRegularFile(it.executable) }
    }

    private fun MutableList<ChromiumBrowser>.addBrowser(
        name: String,
        root: String?,
        vararg path: String,
    ) {
        if (!root.isNullOrBlank()) add(ChromiumBrowser(name, Path.of(root, *path)))
    }

    private fun findAvailableLoopbackPort(): Int {
        return ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    }

    private fun waitForPageTarget(port: Int): URI {
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(DEVTOOLS_START_TIMEOUT_SECONDS)
        var lastError: Throwable? = null

        while (System.nanoTime() < deadline) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/json/list"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                val response = localHttpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(),
                )
                if (response.statusCode() == 200) {
                    val targets = json.parseToJsonElement(response.body()).jsonArray
                    val target = targets
                        .map { it.jsonObject }
                        .firstOrNull {
                            it["type"]?.jsonPrimitive?.contentOrNull == "page" &&
                                it["webSocketDebuggerUrl"] != null
                        }
                    val webSocketUrl = target
                        ?.get("webSocketDebuggerUrl")
                        ?.jsonPrimitive
                        ?.contentOrNull
                    if (webSocketUrl != null) return URI.create(webSocketUrl)
                }
            } catch (error: Throwable) {
                lastError = error
            }
            Thread.sleep(200)
        }

        throw TimeoutException(
            "Could not connect to Chromium DevTools" +
                (lastError?.message?.let { ": $it" } ?: ""),
        )
    }

    private fun seedBrowserCookies(
        session: DevToolsSession,
        url: HttpUrl,
        oldCookies: List<Cookie>,
    ) {
        val cookies = oldCookies
            .filter { it.name != CLEARANCE_COOKIE && it.matches(url) }
            .map { cookie ->
                buildJsonObject {
                    put("name", cookie.name)
                    put("value", cookie.value)
                    put("url", url.toString())
                    put("path", cookie.path)
                    put("secure", cookie.secure)
                    put("httpOnly", cookie.httpOnly)
                    if (cookie.expiresAt != Long.MAX_VALUE) {
                        put("expires", cookie.expiresAt / 1000.0)
                    }
                }
            }
        if (cookies.isNotEmpty()) {
            session.command(
                "Network.setCookies",
                buildJsonObject { put("cookies", JsonArray(cookies)) },
            )
        }
    }

    private fun readBrowserCookies(session: DevToolsSession, url: HttpUrl): List<Cookie> {
        val cookieArray = session.command("Storage.getCookies")["cookies"] as? JsonArray
            ?: return emptyList()
        return cookieArray.mapNotNull { element ->
            val data = element.jsonObject
            val name = data.string("name") ?: return@mapNotNull null
            val value = data.string("value") ?: return@mapNotNull null
            val rawDomain = data.string("domain") ?: return@mapNotNull null
            val domain = rawDomain.removePrefix(".")
            if (!domainMatches(url.host, domain)) return@mapNotNull null

            runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .apply {
                        if (rawDomain.startsWith('.')) domain(domain) else hostOnlyDomain(domain)
                        path(data.string("path") ?: "/")
                        if (data.boolean("secure") == true) secure()
                        if (data.boolean("httpOnly") == true) httpOnly()
                        data.double("expires")
                            ?.takeIf { it > 0.0 }
                            ?.let { expiresAt((it * 1000.0).toLong()) }
                    }
                    .build()
            }.getOrNull()
        }
    }

    private fun readBrowserRequestHeaders(
        session: DevToolsSession,
        originalHeaders: Headers,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val browserVersion = session.command("Browser.getVersion")
        browserVersion.string("userAgent")
            ?.takeIf { it.isNotBlank() }
            ?.let { result["User-Agent"] = it }

        val expression =
            """
            (async () => {
                const data = navigator.userAgentData;
                const output = {
                    userAgent: navigator.userAgent,
                    language: navigator.language,
                };
                if (data) {
                    output.brands = data.brands;
                    Object.assign(output, await data.getHighEntropyValues([
                        'architecture', 'bitness', 'fullVersionList', 'mobile',
                        'model', 'platform', 'platformVersion', 'uaFullVersion'
                    ]));
                }
                return JSON.stringify(output);
            })()
            """.trimIndent()

        runCatching {
            val evaluation = session.command(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("awaitPromise", true)
                    put("returnByValue", true)
                },
            )
            val encoded = evaluation["result"]
                ?.jsonObject
                ?.get("value")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return@runCatching
            val hints = json.parseToJsonElement(encoded).jsonObject

            hints.string("userAgent")?.let { result["User-Agent"] = it }
            hints.string("language")?.let { language ->
                val baseLanguage = language.substringBefore('-')
                result["Accept-Language"] = if (baseLanguage != language) {
                    "$language,$baseLanguage;q=0.9"
                } else {
                    language
                }
            }
            hints.brandHeader("brands")?.let { result["Sec-CH-UA"] = it }
            hints.brandHeader("fullVersionList")?.let {
                result["Sec-CH-UA-Full-Version-List"] = it
            }
            hints.string("uaFullVersion")?.let {
                result["Sec-CH-UA-Full-Version"] = quoteClientHint(it)
            }
            hints.boolean("mobile")?.let {
                result["Sec-CH-UA-Mobile"] = if (it) "?1" else "?0"
            }
            mapOf(
                "architecture" to "Sec-CH-UA-Arch",
                "bitness" to "Sec-CH-UA-Bitness",
                "model" to "Sec-CH-UA-Model",
                "platform" to "Sec-CH-UA-Platform",
                "platformVersion" to "Sec-CH-UA-Platform-Version",
            ).forEach { (jsonName, headerName) ->
                hints.string(jsonName)?.let { result[headerName] = quoteClientHint(it) }
            }
        }.onFailure {
            println("[CloudflareDesktop] Could not read Chromium client hints: ${it.message}")
        }

        if (result["User-Agent"].isNullOrBlank()) {
            result["User-Agent"] = originalHeaders["User-Agent"] ?: FALLBACK_USER_AGENT
        }
        return result
    }

    private fun JsonObject.brandHeader(name: String): String? {
        val brands = this[name] as? JsonArray ?: return null
        return brands.mapNotNull { element ->
            val brand = element.jsonObject.string("brand") ?: return@mapNotNull null
            val version = element.jsonObject.string("version") ?: return@mapNotNull null
            "${quoteClientHint(brand)};v=${quoteClientHint(version)}"
        }.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun quoteClientHint(value: String): String {
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun JsonObject.string(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        return this[name]?.jsonPrimitive?.booleanOrNull
    }

    private fun JsonObject.double(name: String): Double? {
        return this[name]?.jsonPrimitive?.doubleOrNull
    }

    private fun domainMatches(host: String, domain: String): Boolean {
        return host == domain || host.endsWith(".$domain")
    }

    private fun deleteTemporaryProfile(directory: Path) {
        repeat(3) { attempt ->
            val deleted = runCatching {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
                true
            }.getOrDefault(false)
            if (deleted || !Files.exists(directory)) return
            if (attempt < 2) Thread.sleep(250)
        }
        println("[CloudflareDesktop] Could not fully delete temporary profile: $directory")
    }

    private data class ChromiumBrowser(
        val name: String,
        val executable: Path,
    )

    private class DevToolsSession private constructor() : WebSocket.Listener, AutoCloseable {
        private lateinit var webSocket: WebSocket
        private val nextCommandId = AtomicInteger(1)
        private val pendingCommands = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        private val messageBuffer = StringBuilder()

        companion object {
            fun connect(uri: URI): DevToolsSession {
                val session = DevToolsSession()
                session.webSocket = localHttpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, session)
                    .get(5, TimeUnit.SECONDS)
                return session
            }
        }

        fun command(
            method: String,
            params: JsonObject = JsonObject(emptyMap()),
            timeoutSeconds: Long = 5,
        ): JsonObject {
            val id = nextCommandId.getAndIncrement()
            val responseFuture = CompletableFuture<JsonObject>()
            pendingCommands[id] = responseFuture
            val payload = buildJsonObject {
                put("id", id)
                put("method", method)
                if (params.isNotEmpty()) put("params", params)
            }.toString()

            try {
                webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS)
                val response = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS)
                response["error"]?.let { throw IllegalStateException(it.toString()) }
                return response["result"]?.jsonObject ?: JsonObject(emptyMap())
            } finally {
                pendingCommands.remove(id)
            }
        }

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*> {
            var completeMessage: String? = null
            synchronized(messageBuffer) {
                messageBuffer.append(data)
                if (last) {
                    completeMessage = messageBuffer.toString()
                    messageBuffer.setLength(0)
                }
            }
            completeMessage?.let { message ->
                runCatching { json.parseToJsonElement(message).jsonObject }
                    .getOrNull()
                    ?.let { response ->
                        response["id"]
                            ?.jsonPrimitive
                            ?.intOrNull
                            ?.let { pendingCommands.remove(it)?.complete(response) }
                    }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*> {
            failPending(IllegalStateException("Chromium DevTools closed: $statusCode $reason"))
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            failPending(error)
        }

        private fun failPending(error: Throwable) {
            pendingCommands.values.forEach { it.completeExceptionally(error) }
            pendingCommands.clear()
        }

        override fun close() {
            runCatching {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                    .get(1, TimeUnit.SECONDS)
            }
        }
    }

    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
