import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.awt.image.BufferedImage
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * LibVLC state adapter using direct rendering.
 *
 * LibVLC decodes into a native RV32 buffer and Compose draws the resulting
 * image. No AWT/Swing video component is embedded, so Compose overlays remain
 * visible and interactive.
 */
class VlcPlayerState internal constructor() {
    var isPlaying by mutableStateOf(false)
        private set
    var currentTime by mutableStateOf(0.0)
        private set
    var duration by mutableStateOf(0.0)
        private set
    var sliderPos by mutableStateOf(0f)
        private set
    var volume by mutableStateOf(0.5f)
        private set
    var playbackSpeed by mutableStateOf(1.0f)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var isFullscreen by mutableStateOf(false)

    private val frameRenderer = ComposeFrameRenderer { nextFrame ->
        frame = nextFrame
    }
    private val mediaPlayerFactory = MediaPlayerFactory(
        "--no-video-title-show",
        "--network-caching=1000",
        "--drop-late-frames",
        "--skip-frames",
    )
    private val mediaPlayer: EmbeddedMediaPlayer = mediaPlayerFactory.mediaPlayers().newEmbeddedMediaPlayer().apply {
        videoSurface().set(
            mediaPlayerFactory.videoSurfaces().newVideoSurface(
                frameRenderer,
                frameRenderer,
                true,
            ),
        )
    }

    val canRender: Boolean
        get() = errorMessage == null
    val positionText: String
        get() = formatTime(currentTime)
    val durationText: String
        get() = formatTime(duration)

    suspend fun verifyAndOpen(uri: String) {
        errorMessage = null
        frame = null
        val responseCode = withContext(Dispatchers.IO) {
            val connection = (URL(uri).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }

        if (responseCode in 200..299) {
            mediaPlayer.media().play(uri)
        } else {
            errorMessage = "El servidor de video rechazó la reproducción (HTTP $responseCode). Actualiza la lista de servidores o prueba otro servidor."
        }
    }

    fun play() {
        mediaPlayer.controls().play()
    }

    fun pause() {
        mediaPlayer.controls().pause()
    }

    fun stop() {
        mediaPlayer.controls().stop()
        refresh()
    }

    fun seekStart(position: Float) {
        sliderPos = position.coerceIn(0f, 1000f)
    }

    fun seekFinished() {
        seekTo(sliderPos)
    }

    fun seekTo(position: Float) {
        val normalizedPosition = position.coerceIn(0f, 1000f) / 1000f
        mediaPlayer.controls().setPosition(normalizedPosition)
        sliderPos = normalizedPosition * 1000f
    }

    fun updateVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        mediaPlayer.audio().setVolume((volume * 100).toInt())
    }

    fun updatePlaybackSpeed(value: Float) {
        playbackSpeed = value.coerceIn(0.25f, 4f)
        mediaPlayer.controls().setRate(playbackSpeed)
    }

    fun refresh() {
        val lengthMillis = mediaPlayer.status().length()
        val timeMillis = mediaPlayer.status().time()
        duration = lengthMillis.coerceAtLeast(0).toDouble()
        currentTime = timeMillis.coerceAtLeast(0) / 1000.0
        sliderPos = if (lengthMillis > 0) {
            (timeMillis.toFloat() / lengthMillis.toFloat() * 1000f).coerceIn(0f, 1000f)
        } else {
            0f
        }
        isPlaying = mediaPlayer.status().isPlaying()
    }

    fun release() {
        mediaPlayer.release()
        mediaPlayerFactory.release()
    }

    private fun formatTime(value: Double): String {
        val seconds = (if (value > 100_000) value / 1000 else value).toLong().coerceAtLeast(0)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}

@Composable
fun rememberVlcPlayerState(): VlcPlayerState = remember { VlcPlayerState() }

@Composable
fun VlcPlayerSurface(
    playerState: VlcPlayerState,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(playerState) {
        onDispose { playerState.release() }
    }

    LaunchedEffect(playerState) {
        while (true) {
            playerState.refresh()
            delay(250)
        }
    }

    Box(
        modifier = modifier.background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        playerState.frame?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private class ComposeFrameRenderer(
    private val onFrame: (ImageBitmap) -> Unit,
) : BufferFormatCallback, RenderCallback {
    private var width = 0
    private var height = 0
    private val framePending = AtomicBoolean(false)

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        width = sourceWidth
        height = sourceHeight
        return RV32BufferFormat(sourceWidth, sourceHeight)
    }

    override fun allocatedBuffers(buffers: Array<ByteBuffer>) = Unit

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
        if (width <= 0 || height <= 0 || nativeBuffers.isEmpty() || !framePending.compareAndSet(false, true)) {
            return
        }

        val pixels = IntArray(width * height)
        val source = nativeBuffers[0].duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.rewind()
        source.asIntBuffer().get(pixels, 0, pixels.size)
        for (index in pixels.indices) {
            pixels[index] = pixels[index] or 0xFF000000.toInt()
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, pixels, 0, width)
        }.toComposeImageBitmap()

        SwingUtilities.invokeLater {
            try {
                onFrame(image)
            } finally {
                framePending.set(false)
            }
        }
    }
}
