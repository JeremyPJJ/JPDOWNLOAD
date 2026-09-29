package com.example.videodownload.data.repository

import android.util.Log
import com.example.videodownload.data.model.DownloadOption
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ExtractorResult(
    val success: Boolean,
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
    val title: String? = null,
    val author: String? = null,
    val source: String? = null,
    val error: String? = null
)

interface PlatformExtractor {
    val platform: Platform
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): Result<VideoInfo>
}

class VideoExtractorRepository {

    companion object {
        const val TAG = "VideoExtractor"
    }

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .build()

    private val extractors: List<PlatformExtractor> by lazy {
        listOf(
            TikTokExtractor(client),
            TwitterExtractor(client),
            InstagramExtractor(client),
            FacebookExtractor(client),
            YouTubeExtractor(client)
        )
    }

    fun detectPlatform(url: String): Platform {
        val lower = url.lowercase()
        return when {
            lower.contains("tiktok.com") || lower.contains("vt.tiktok") -> Platform.TIKTOK
            lower.contains("youtube.com") || lower.contains("youtu.be") -> Platform.YOUTUBE
            lower.contains("instagram.com") || lower.contains("instagr.am") -> Platform.INSTAGRAM
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> Platform.FACEBOOK
            lower.contains("twitter.com") || lower.contains("x.com") -> Platform.TWITTER
            else -> Platform.UNKNOWN
        }
    }

    suspend fun extractVideoInfo(rawUrl: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val firstUrl = extractFirstUrl(rawUrl) ?: rawUrl.trim()
        val platform = detectPlatform(firstUrl)

        Log.d(TAG, "==================================================")
        Log.d(TAG, "[extractVideoInfo] URL Recibida: '$rawUrl'")
        Log.d(TAG, "[extractVideoInfo] URL Procesada: '$firstUrl' | Plataforma Detectada: ${platform.displayName}")

        val targetExtractor = extractors.find { it.canHandle(firstUrl) }

        if (targetExtractor != null) {
            Log.d(TAG, "[extractVideoInfo] Asignando a Extractor Dedicado: ${targetExtractor.javaClass.simpleName}")
            val result = targetExtractor.extract(firstUrl)
            if (result.isSuccess) {
                return@withContext result
            }
            Log.w(TAG, "[extractVideoInfo] ${targetExtractor.javaClass.simpleName} falló: ${result.exceptionOrNull()?.message}")
            return@withContext result
        }

        return@withContext Result.failure(Exception("No se pudo detectar un extractor para la plataforma."))
    }

    private fun extractFirstUrl(text: String): String? {
        val urlPattern = Pattern.compile(
            "(https?://[\\w-]+(\\.[\\w-]+)+[\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE TIKTOK
// ============================================================================
class TikTokExtractor(private val client: OkHttpClient) : PlatformExtractor {
    override val platform = Platform.TIKTOK

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com") || lower.contains("vt.tiktok")
    }

    override suspend fun extract(url: String): Result<VideoInfo> {
        Log.d(VideoExtractorRepository.TAG, "[TikTokExtractor] Procesando URL: $url")
        val apiUrl = "https://www.tikwm.com/api/?url=${url}"
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                Log.d(VideoExtractorRepository.TAG, "[TikTokExtractor] HTTP Code: ${response.code}")
                if (!response.isSuccessful) {
                    return Result.failure(Exception("Error al conectar con la API de TikWM (${response.code})"))
                }

                val bodyString = response.body?.string() ?: return Result.failure(Exception("Respuesta vacía"))
                val json = JsonParser.parseString(bodyString).asJsonObject

                val code = json.get("code")?.asInt ?: -1
                if (code == 0) {
                    val data = json.getAsJsonObject("data")
                    val id = data.get("id")?.asString ?: System.currentTimeMillis().toString()
                    val title = data.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "TikTok Video"
                    val cover = data.get("cover")?.asString ?: ""
                    var playUrl = data.get("play")?.asString ?: ""
                    var musicUrl = data.get("music")?.asString ?: ""

                    if (playUrl.startsWith("/")) {
                        playUrl = "https://www.tikwm.com$playUrl"
                    }
                    if (musicUrl.startsWith("/")) {
                        musicUrl = "https://www.tikwm.com$musicUrl"
                    }

                    val authorObj = data.getAsJsonObject("author")
                    val author = authorObj?.get("nickname")?.asString
                        ?: authorObj?.get("unique_id")?.asString
                        ?: "TikTok User"

                    val options = mutableListOf(
                        DownloadOption("Full HD (1080p)", playUrl, isAudio = false, quality = "1080p", extension = "mp4"),
                        DownloadOption("HD (720p)", playUrl, isAudio = false, quality = "720p", extension = "mp4")
                    )

                    if (musicUrl.isNotBlank()) {
                        options.add(DownloadOption("Audio MP3", musicUrl, isAudio = true, quality = "MP3", extension = "mp3"))
                    } else {
                        options.add(DownloadOption("Audio MP3", playUrl, isAudio = true, quality = "MP3", extension = "mp3"))
                    }

                    val videoInfo = VideoInfo(
                        id = id,
                        title = title,
                        author = author,
                        thumbnailUrl = cover,
                        downloadUrl = playUrl,
                        platform = Platform.TIKTOK,
                        quality = "1080p Full HD",
                        isWatermarkFree = true,
                        originalUrl = url,
                        options = options
                    )
                    Log.i(VideoExtractorRepository.TAG, "[TikTokExtractor] ÉXITO -> $playUrl")
                    return Result.success(videoInfo)
                } else {
                    val msg = json.get("msg")?.asString ?: "Error de extracción TikTok"
                    Log.w(VideoExtractorRepository.TAG, "[TikTokExtractor] FALLO: $msg")
                    return Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Log.e(VideoExtractorRepository.TAG, "[TikTokExtractor] Excepción: ${e.message}", e)
            return Result.failure(e)
        }
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE TWITTER / X
// ============================================================================
class TwitterExtractor(private val client: OkHttpClient) : PlatformExtractor {
    override val platform = Platform.TWITTER

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("twitter.com") || lower.contains("x.com")
    }

    override suspend fun extract(url: String): Result<VideoInfo> {
        val statusMatcher = Pattern.compile("status/(\\d+)").matcher(url)
        val statusId = if (statusMatcher.find()) statusMatcher.group(1) else null

        Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] Procesando URL: $url | Status ID: $statusId")

        if (statusId.isNullOrBlank()) {
            return Result.failure(Exception("No se pudo extraer el ID del Tweet de Twitter/X."))
        }

        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1. VxTwitter API
        val vxEndpoints = listOf(
            "https://api.vxtwitter.com/status/$statusId",
            "https://api.vxtwitter.com/Twitter/status/$statusId",
            "https://api.vxtwitter.com/i/status/$statusId"
        )

        for (vxUrl in vxEndpoints) {
            try {
                Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] Intentando VxTwitter API: $vxUrl")
                val request = Request.Builder()
                    .url(vxUrl)
                    .addHeader("User-Agent", desktopUserAgent)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] VxTwitter HTTP Code: ${response.code}")
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.isNotBlank() && body.startsWith("{")) {
                            val json = JsonParser.parseString(body).asJsonObject
                            val text = json.get("text")?.asString?.takeIf { it.isNotBlank() } ?: "Twitter / X Video"
                            val userName = json.get("user_name")?.asString
                                ?: json.get("user_screen_name")?.asString
                                ?: "Twitter User"

                            val mediaExtended = json.getAsJsonArray("media_extended")
                            if (mediaExtended != null && mediaExtended.size() > 0) {
                                for (i in 0 until mediaExtended.size()) {
                                    val media = mediaExtended.get(i).asJsonObject
                                    val type = media.get("type")?.asString ?: ""
                                    val videoUrl = media.get("url")?.asString ?: ""
                                    val thumb = media.get("thumbnail_url")?.asString ?: ""

                                    if (videoUrl.isNotBlank() && (type == "video" || type == "gif" || videoUrl.contains(".mp4"))) {
                                        val options = listOf(
                                            DownloadOption("Full HD (1080p)", videoUrl, isAudio = false, quality = "1080p", extension = "mp4"),
                                            DownloadOption("HD (720p)", videoUrl, isAudio = false, quality = "720p", extension = "mp4"),
                                            DownloadOption("Audio MP3", videoUrl, isAudio = true, quality = "MP3", extension = "mp3")
                                        )

                                        Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] VxTwitter ÉXITO -> $videoUrl")
                                        return Result.success(
                                            VideoInfo(
                                                id = statusId,
                                                title = text,
                                                author = userName,
                                                thumbnailUrl = thumb,
                                                downloadUrl = videoUrl,
                                                platform = Platform.TWITTER,
                                                quality = "1080p Full HD",
                                                isWatermarkFree = true,
                                                originalUrl = url,
                                                options = options
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Excepción en VxTwitter: ${e.message}")
            }
        }

        return Result.failure(Exception("No se pudo obtener el video de Twitter/X."))
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE YOUTUBE (NewPipeExtractor v0.26.5 con Muxer de Audio)
// ============================================================================
class NewPipeOkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    private val cookieMap = ConcurrentHashMap<String, String>()

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36")
            .addHeader("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")

        headers?.forEach { (key, values) ->
            values.forEach { value ->
                requestBuilder.addHeader(key, value)
            }
        }

        val host = try { url.toHttpUrl().host } catch (_: Exception) { "" }
        if (host.isNotBlank() && cookieMap.containsKey(host)) {
            requestBuilder.addHeader("Cookie", cookieMap[host] ?: "")
        }

        val body = dataToSend?.toRequestBody("application/json; charset=utf-8".toMediaType())
        when (httpMethod) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(body ?: "".toRequestBody())
            "HEAD" -> requestBuilder.head()
            else -> requestBuilder.method(httpMethod, body)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseCode = response.code
            val responseMessage = response.message
            val responseHeaders = mutableMapOf<String, List<String>>()

            response.headers.names().forEach { name ->
                responseHeaders[name] = response.headers(name)
            }

            val setCookie = response.headers("Set-Cookie")
            if (setCookie.isNotEmpty()) {
                val combinedCookie = setCookie.joinToString("; ")
                if (host.isNotBlank()) {
                    cookieMap[host] = combinedCookie
                }
            }

            val responseBody = response.body?.string() ?: ""

            Log.d(VideoExtractorRepository.TAG, "[NewPipeDownloader] HTTP $responseCode | URL: $url | Body (200 chars): ${responseBody.take(200)}")

            return org.schabi.newpipe.extractor.downloader.Response(responseCode, responseMessage, responseHeaders, responseBody, url)
        }
    }
}

class YouTubeExtractor(private val client: OkHttpClient) : PlatformExtractor {
    override val platform = Platform.YOUTUBE

    companion object {
        const val NEWPIPE_VERSION = "v0.26.5"
    }

    init {
        try {
            NewPipe.init(NewPipeOkHttpDownloader(client))
            Log.i(VideoExtractorRepository.TAG, "[YouTubeExtractor] NewPipe.init() inicializado | Versión: $NEWPIPE_VERSION")
        } catch (e: Exception) {
            Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] NewPipe.init notice: ${e.message}")
        }
    }

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun extractVideoId(rawUrl: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:v=|/videos/|embed/|shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^([A-Za-z0-9_-]{11})$")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(rawUrl)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    override suspend fun extract(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
        val canonicalYoutubeUrl = if (!videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=$videoId" else url.split("?")[0]

        Log.d(VideoExtractorRepository.TAG, "==================================================")
        Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] NewPipeExtractor Versión En Uso: $NEWPIPE_VERSION")
        Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] Raw URL: '$url'")
        Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] Extracted Video ID: '$videoId'")
        Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] Canonical YouTube URL: '$canonicalYoutubeUrl'")

        if (videoId.isNullOrBlank()) {
            return@withContext Result.failure(Exception("No se pudo extraer el ID del video de YouTube."))
        }

        // METODO 1: NewPipeExtractor v0.26.5
        try {
            Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] Ejecutando NewPipeExtractor $NEWPIPE_VERSION para '$canonicalYoutubeUrl'...")
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, canonicalYoutubeUrl)

            if (streamInfo != null) {
                val title = streamInfo.name ?: "YouTube Video"
                val uploader = streamInfo.uploaderName ?: "YouTube Channel"
                val thumb = streamInfo.thumbnails.firstOrNull()?.url ?: ""

                val optionsList = mutableListOf<DownloadOption>()

                // Obtener la mejor pista de audio M4A/AAC para unificar con videos DASH
                val audioStreams = streamInfo.audioStreams
                val bestAudioUrl = audioStreams?.firstOrNull()?.content

                // 1. Streams de video combinados (Progressive MP4 - Video + Audio integrados)
                val videoStreams = streamInfo.videoStreams
                if (videoStreams != null && videoStreams.isNotEmpty()) {
                    videoStreams.forEach { stream ->
                        val streamUrl = stream.content
                        val resolution = stream.resolution ?: "720p"
                        if (!streamUrl.isNullOrBlank()) {
                            optionsList.add(
                                DownloadOption(
                                    label = "$resolution HD",
                                    downloadUrl = streamUrl,
                                    isAudio = false,
                                    quality = resolution,
                                    extension = "mp4"
                                )
                            )
                        }
                    }
                }

                // 2. Streams de audio solo (MP3 / M4A)
                if (audioStreams != null && audioStreams.isNotEmpty()) {
                    val firstAudio = audioStreams[0]
                    val audioUrl = firstAudio.content
                    val ext = "m4a"

                    if (!audioUrl.isNullOrBlank()) {
                        optionsList.add(
                            DownloadOption(
                                label = "Audio MP3",
                                downloadUrl = audioUrl,
                                isAudio = true,
                                quality = "MP3",
                                extension = ext
                            )
                        )
                    }
                }

                // 3. Streams de video alta definición (1080p, 720p60, 480p) -> Muxing automático con Audio M4A
                val videoOnlyStreams = streamInfo.videoOnlyStreams
                if (videoOnlyStreams != null && videoOnlyStreams.isNotEmpty()) {
                    videoOnlyStreams.forEach { stream ->
                        val streamUrl = stream.content
                        val resolution = stream.resolution ?: "1080p"
                        if (!streamUrl.isNullOrBlank() && optionsList.none { it.quality == resolution }) {
                            val cleanLabel = when {
                                resolution.contains("1080") -> "1080p Full HD"
                                resolution.contains("1440") -> "1440p 2K"
                                resolution.contains("2160") || resolution.contains("4k") -> "4K Ultra HD"
                                else -> "$resolution HD"
                            }
                            optionsList.add(
                                DownloadOption(
                                    label = cleanLabel,
                                    downloadUrl = streamUrl,
                                    isAudio = false,
                                    quality = resolution,
                                    extension = "mp4",
                                    audioDownloadUrl = bestAudioUrl
                                )
                            )
                        }
                    }
                }

                if (optionsList.isNotEmpty()) {
                    Log.i(VideoExtractorRepository.TAG, "[YouTubeExtractor] NewPipeExtractor $NEWPIPE_VERSION ÉXITO -> ${optionsList.size} calidades encontradas")
                    return@withContext Result.success(
                        VideoInfo(
                            id = videoId,
                            title = title,
                            author = uploader,
                            thumbnailUrl = thumb,
                            downloadUrl = optionsList[0].downloadUrl,
                            platform = Platform.YOUTUBE,
                            quality = optionsList[0].quality,
                            isWatermarkFree = true,
                            originalUrl = url,
                            options = optionsList
                        )
                    )
                }
            }
        } catch (e: ContentNotAvailableException) {
            val msg = e.message ?: ""
            Log.e(VideoExtractorRepository.TAG, "[YouTubeExtractor] ContentNotAvailableException: $msg")
            if (msg.contains("reloaded", ignoreCase = true) || msg.contains("UNPLAYABLE", ignoreCase = true)) {
                return@withContext Result.failure(Exception("YouTube bloqueó la extracción. Actualiza la app/librería."))
            }
            return@withContext Result.failure(Exception("YouTube reportó: $msg"))
        } catch (e: ParsingException) {
            val msg = e.message ?: ""
            Log.e(VideoExtractorRepository.TAG, "[YouTubeExtractor] ParsingException: $msg")
            if (msg.contains("reloaded", ignoreCase = true) || msg.contains("UNPLAYABLE", ignoreCase = true)) {
                return@withContext Result.failure(Exception("YouTube bloqueó la extracción. Actualiza la app/librería."))
            }
            return@withContext Result.failure(Exception("Fallo de análisis de YouTube ($msg)."))
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.e(VideoExtractorRepository.TAG, "[YouTubeExtractor] Excepción General: $msg | Class: ${e.javaClass.simpleName}", e)
            if (msg.contains("reloaded", ignoreCase = true) || msg.contains("UNPLAYABLE", ignoreCase = true)) {
                return@withContext Result.failure(Exception("YouTube bloqueó la extracción. Actualiza la app/librería."))
            }
            return@withContext Result.failure(Exception("Error en NewPipeExtractor: $msg (${e.javaClass.simpleName})"))
        }

        return@withContext Result.failure(Exception("No se pudo obtener el video de YouTube. Revisa los logs en Logcat."))
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE INSTAGRAM
// ============================================================================
class InstagramExtractor(private val client: OkHttpClient) : PlatformExtractor {
    override val platform = Platform.INSTAGRAM

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("instagram.com") || lower.contains("instagr.am")
    }

    private fun extractShortcode(url: String): String? {
        val patterns = listOf(
            Pattern.compile("/(?:p|reel|reels|tv|share/p|share/reel)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("instagram\\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE)
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                val code = matcher.group(1)
                if (!code.isNullOrBlank() && code.length >= 5) {
                    return code
                }
            }
        }
        return null
    }

    override suspend fun extract(url: String): Result<VideoInfo> {
        val cleanUrl = url.split("?")[0].trim()
        val shortcode = extractShortcode(cleanUrl)
        val canonicalUrl = if (!shortcode.isNullOrBlank()) "https://www.instagram.com/reel/$shortcode/" else cleanUrl

        Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] URL: '$url' | Canónica: '$canonicalUrl' | Shortcode: '$shortcode'")

        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1. Publer Media API
        try {
            val jsonPayload = JsonObject().apply {
                addProperty("url", canonicalUrl)
            }.toString()

            Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] Intentando Publer API...")
            val request = Request.Builder()
                .url("https://publer.io/api/v1/media/download")
                .addHeader("User-Agent", desktopUserAgent)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Origin", "https://publer.io")
                .addHeader("Referer", "https://publer.io/")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] Publer HTTP Code: ${response.code}")
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isNotBlank() && bodyString.startsWith("{")) {
                        val json = JsonParser.parseString(bodyString).asJsonObject
                        val path = json.get("path")?.asString
                            ?: json.get("url")?.asString
                            ?: json.getAsJsonArray("payload")?.get(0)?.asJsonObject?.get("path")?.asString ?: ""

                        if (path.isNotBlank() && path.startsWith("http")) {
                            val options = listOf(
                                DownloadOption("Full HD (1080p)", path, isAudio = false, quality = "1080p", extension = "mp4"),
                                DownloadOption("HD (720p)", path, isAudio = false, quality = "720p", extension = "mp4"),
                                DownloadOption("SD (480p)", path, isAudio = false, quality = "480p", extension = "mp4"),
                                DownloadOption("Audio MP3", path, isAudio = true, quality = "MP3", extension = "mp3")
                            )

                            Log.i(VideoExtractorRepository.TAG, "[InstagramExtractor] Publer ÉXITO -> $path")
                            return Result.success(
                                VideoInfo(
                                    id = shortcode ?: System.currentTimeMillis().toString(),
                                    title = "Instagram Reel",
                                    author = "Instagram User",
                                    thumbnailUrl = "",
                                    downloadUrl = path,
                                    platform = Platform.INSTAGRAM,
                                    quality = "1080p Full HD",
                                    isWatermarkFree = true,
                                    originalUrl = url,
                                    options = options
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[InstagramExtractor] Excepción Publer: ${e.message}")
        }

        // 2. FastDL V3 API
        try {
            val jsonPayload = JsonObject().apply {
                addProperty("url", canonicalUrl)
            }.toString()

            Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] Intentando FastDL V3 API...")
            val request = Request.Builder()
                .url("https://v3.fastdl.app/api/convert")
                .addHeader("User-Agent", desktopUserAgent)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Origin", "https://fastdl.app")
                .addHeader("Referer", "https://fastdl.app/")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] FastDL HTTP Code: ${response.code}")
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isNotBlank() && bodyString.startsWith("{")) {
                        val json = JsonParser.parseString(bodyString).asJsonObject
                        val urlArray = json.getAsJsonArray("url")
                        if (urlArray != null && urlArray.size() > 0) {
                            val firstUrl = urlArray.get(0).asJsonObject.get("url")?.asString ?: ""
                            if (firstUrl.isNotBlank() && firstUrl.startsWith("http")) {
                                val options = listOf(
                                    DownloadOption("Full HD (1080p)", firstUrl, isAudio = false, quality = "1080p", extension = "mp4"),
                                    DownloadOption("HD (720p)", firstUrl, isAudio = false, quality = "720p", extension = "mp4"),
                                    DownloadOption("Audio MP3", firstUrl, isAudio = true, quality = "MP3", extension = "mp3")
                                )

                                Log.i(VideoExtractorRepository.TAG, "[InstagramExtractor] FastDL ÉXITO -> $firstUrl")
                                return Result.success(
                                    VideoInfo(
                                        id = shortcode ?: System.currentTimeMillis().toString(),
                                        title = "Instagram Reel",
                                        author = "Instagram User",
                                        thumbnailUrl = "",
                                        downloadUrl = firstUrl,
                                        platform = Platform.INSTAGRAM,
                                        quality = "1080p Full HD",
                                        isWatermarkFree = true,
                                        originalUrl = url,
                                        options = options
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[InstagramExtractor] Excepción FastDL: ${e.message}")
        }

        return Result.failure(Exception("No se pudo extraer el video de Instagram."))
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE FACEBOOK
// ============================================================================
class FacebookExtractor(private val client: OkHttpClient) : PlatformExtractor {
    override val platform = Platform.FACEBOOK

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com")
    }

    private fun expandUrl(rawUrl: String): String {
        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        try {
            val request = Request.Builder()
                .url(rawUrl)
                .addHeader("User-Agent", desktopUserAgent)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (finalUrl.isNotBlank()) {
                    val html = response.body?.string() ?: ""
                    val canonicalMatcher = Pattern.compile("<link\\s+rel=[\"']canonical[\"']\\s+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (canonicalMatcher.find()) {
                        val canonical = canonicalMatcher.group(1)
                        if (!canonical.isNullOrBlank() && canonical.contains("facebook.com")) {
                            return canonical
                        }
                    }
                    return finalUrl
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Excepción expandiendo URL: ${e.message}")
        }
        return rawUrl
    }

    override suspend fun extract(url: String): Result<VideoInfo> {
        val expandedUrl = expandUrl(url)
        val cleanUrl = expandedUrl.split("?")[0]
        Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] URL Original: $url | Expandida: $expandedUrl | Limpia: $cleanUrl")

        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1. Publer Media API
        try {
            val jsonPayload = JsonObject().apply {
                addProperty("url", cleanUrl)
            }.toString()

            Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] Intentando Publer API...")
            val request = Request.Builder()
                .url("https://publer.io/api/v1/media/download")
                .addHeader("User-Agent", desktopUserAgent)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Origin", "https://publer.io")
                .addHeader("Referer", "https://publer.io/")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] Publer HTTP Code: ${response.code}")
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isNotBlank() && bodyString.startsWith("{")) {
                        val json = JsonParser.parseString(bodyString).asJsonObject
                        val path = json.get("path")?.asString
                            ?: json.get("url")?.asString
                            ?: json.getAsJsonArray("payload")?.get(0)?.asJsonObject?.get("path")?.asString ?: ""

                        if (path.isNotBlank() && path.startsWith("http")) {
                            val options = listOf(
                                DownloadOption("Full HD (1080p)", path, isAudio = false, quality = "1080p", extension = "mp4"),
                                DownloadOption("HD (720p)", path, isAudio = false, quality = "720p", extension = "mp4"),
                                DownloadOption("SD (480p)", path, isAudio = false, quality = "480p", extension = "mp4"),
                                DownloadOption("Audio MP3", path, isAudio = true, quality = "MP3", extension = "mp3")
                            )

                            Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] Publer ÉXITO -> $path")
                            return Result.success(
                                VideoInfo(
                                    id = System.currentTimeMillis().toString(),
                                    title = "Facebook Video",
                                    author = "Facebook User",
                                    thumbnailUrl = "",
                                    downloadUrl = path,
                                    platform = Platform.FACEBOOK,
                                    quality = "1080p Full HD",
                                    isWatermarkFree = true,
                                    originalUrl = url,
                                    options = options
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Excepción Publer: ${e.message}")
        }

        // 2. Facebook MBasic Scraper
        try {
            val mobileUserAgent = "Mozilla/5.0 (Android 13; Mobile; rv:122.0) Gecko/122.0 Firefox/122.0"
            val mbasicUrl = cleanUrl.replace("www.facebook.com", "mbasic.facebook.com")
                .replace("m.facebook.com", "mbasic.facebook.com")

            Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] Intentando MBasic: $mbasicUrl")
            val request = Request.Builder()
                .url(mbasicUrl)
                .addHeader("User-Agent", mobileUserAgent)
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] MBasic HTTP Code: ${response.code}")
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val redirectMatcher = Pattern.compile("href=[\"']/video_redirect/\\?src=([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (redirectMatcher.find()) {
                        val rawEncodedUrl = redirectMatcher.group(1)
                        if (!rawEncodedUrl.isNullOrBlank()) {
                            val decodedUrl = URLDecoder.decode(rawEncodedUrl, "UTF-8").replace("&amp;", "&")
                            if (decodedUrl.startsWith("http")) {
                                val options = listOf(
                                    DownloadOption("Video MP4 (HD)", decodedUrl, isAudio = false, quality = "HD", extension = "mp4"),
                                    DownloadOption("Audio MP3", decodedUrl, isAudio = true, quality = "MP3", extension = "mp3")
                                )

                                Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] MBasic ÉXITO -> $decodedUrl")
                                return Result.success(
                                    VideoInfo(
                                        id = System.currentTimeMillis().toString(),
                                        title = "Facebook Video",
                                        author = "Facebook User",
                                        thumbnailUrl = "",
                                        downloadUrl = decodedUrl,
                                        platform = Platform.FACEBOOK,
                                        quality = "HD",
                                        isWatermarkFree = true,
                                        originalUrl = url,
                                        options = options
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Excepción MBasic: ${e.message}")
        }

        return Result.failure(Exception("No se pudo extraer el video de Facebook."))
    }
}
