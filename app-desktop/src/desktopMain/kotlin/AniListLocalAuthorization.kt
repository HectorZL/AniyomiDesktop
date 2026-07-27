import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val ANI_LIST_CALLBACK_PORT = 42731
private const val ANI_LIST_CALLBACK_PATH = "/anilist/callback"
private const val ANI_LIST_COMPLETE_PATH = "/anilist/complete"
private const val ANI_LIST_REDIRECT_URI = "http://127.0.0.1:$ANI_LIST_CALLBACK_PORT$ANI_LIST_CALLBACK_PATH"

/**
 * Receives AniList's implicit OAuth response on localhost. OAuth tokens are returned in a URL
 * fragment, which browsers never send to the server, so the callback page forwards that fragment
 * to a second local endpoint before confirming the connection to the user.
 */
class AniListLocalAuthorization private constructor(
    private val server: HttpServer,
    private val state: String,
    private val onToken: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val completed = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AniList OAuth timeout").apply { isDaemon = true }
    }

    fun authorizationUrl(clientId: String): String {
        val encodedClientId = URLEncoder.encode(clientId, StandardCharsets.UTF_8.toString())
        val encodedRedirectUri = URLEncoder.encode(ANI_LIST_REDIRECT_URI, StandardCharsets.UTF_8.toString())
        val encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8.toString())
        return "https://anilist.co/api/v2/oauth/authorize?client_id=$encodedClientId&response_type=token&redirect_uri=$encodedRedirectUri&state=$encodedState"
    }

    fun cancel() {
        finishFailure("Se canceló el inicio de sesión de AniList.")
    }

    private fun receiveCompletion(exchange: HttpExchange) {
        val parameters = parseParameters(exchange.requestURI)
        val receivedState = parameters["state"]
        val token = parameters["access_token"]?.trim()
        if (receivedState != state) {
            respond(exchange, 400, "<h2>La solicitud de AniList no coincide con la app.</h2><p>Vuelve a iniciar la conexión desde Aniyomi.</p>")
            return
        }
        if (token.isNullOrBlank()) {
            respond(exchange, 400, "<h2>No se recibió el token de AniList.</h2><p>Vuelve a iniciar la conexión desde Aniyomi.</p>")
            finishFailure("AniList no devolvió un token de acceso.")
            return
        }

        respond(exchange, 200, "<h2>Cuenta conectada</h2><p>Puedes cerrar esta pestaña y volver a Aniyomi.</p>")
        if (completed.compareAndSet(false, true)) {
            stop()
            onToken(token)
        }
    }

    private fun finishFailure(message: String) {
        if (completed.compareAndSet(false, true)) {
            stop()
            onFailure(message)
        }
    }

    private fun stop() {
        scheduler.shutdownNow()
        server.stop(0)
    }

    companion object {
        fun start(
            onToken: (String) -> Unit,
            onFailure: (String) -> Unit,
        ): AniListLocalAuthorization {
            val state = UUID.randomUUID().toString()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", ANI_LIST_CALLBACK_PORT), 0)
            val authorization = AniListLocalAuthorization(server, state, onToken, onFailure)
            server.createContext(ANI_LIST_CALLBACK_PATH) { exchange ->
                respond(exchange, 200, callbackPage(state))
            }
            server.createContext(ANI_LIST_COMPLETE_PATH) { exchange -> authorization.receiveCompletion(exchange) }
            server.executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "AniList OAuth callback").apply { isDaemon = true }
            }
            server.start()
            authorization.scheduler.schedule(
                { authorization.finishFailure("Se agotó el tiempo de espera para iniciar sesión en AniList.") },
                5,
                TimeUnit.MINUTES,
            )
            return authorization
        }

        private fun callbackPage(expectedState: String): String = """
            <!doctype html>
            <html lang="es"><head><meta charset="utf-8"><title>Conectando AniList</title></head>
            <body><h2>Conectando con Aniyomi…</h2><p>Espera un momento.</p>
            <script>
              const parameters = new URLSearchParams(window.location.hash.substring(1));
              parameters.set('state', '${expectedState}');
              fetch('${ANI_LIST_COMPLETE_PATH}?' + parameters.toString())
                .then(() => document.body.innerHTML = '<h2>Cuenta conectada</h2><p>Puedes cerrar esta pestaña y volver a Aniyomi.</p>')
                .catch(() => document.body.innerHTML = '<h2>No se pudo comunicar con Aniyomi</h2><p>Vuelve a iniciar la conexión desde la aplicación.</p>');
            </script></body></html>
        """.trimIndent()

        private fun parseParameters(uri: URI): Map<String, String> = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator < 0) null else {
                    URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8.toString()) to
                        URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8.toString())
                }
            }
            ?.toMap()
            ?: emptyMap()

        private fun respond(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
