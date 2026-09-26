package com.example.videodownload.data.repository

import android.util.Log
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
        }

        // Fallback Multi-Plataforma si el extractor dedicado no tuvo éxito
        Log.w(TAG, "[extractVideoInfo] Ejecutando Cobalt Multi-Platform Fallback...")
        extractMultiPlatform(firstUrl, platform)
    }

    private fun extractMultiPlatform(rawUrl: String, platform: Platform): Result<VideoInfo> {
        val cleanUrl = rawUrl.split("?")[0].replace("x.com", "twitter.com")
        val cobaltInstances = listOf(
            "https://api.cobalt.tools/",
            "https://cobalt-api.kwiatek.xyz/",
            "https://co.wuk.sh/"
        )

        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (instanceUrl in cobaltInstances) {
            try {
                Log.d(TAG, "[Cobalt Fallback] Endpoint: $instanceUrl | URL: $cleanUrl")

                val jsonPayload = JsonObject().apply {
                    addProperty("url", cleanUrl)
                    addProperty("videoQuality", "720")
                }.toString()

                val request = Request.Builder()
                    .url(instanceUrl)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .post(jsonPayload.toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""
                    Log.d(TAG, "[Cobalt Fallback] Code: $code | Body Length: ${bodyString.length}")

                    if (response.isSuccessful && bodyString.isNotBlank()) {
                        val json = JsonParser.parseString(bodyString).asJsonObject
                        val status = json.get("status")?.asString ?: ""

                        if (status == "redirect" || status == "stream" || status == "tunnel") {
                            val downloadUrl = json.get("url")?.asString ?: ""
                            if (downloadUrl.isNotBlank()) {
                                Log.i(TAG, "[Cobalt Fallback] ÉXITO -> Video URL: $downloadUrl")
                                return Result.success(
                                    VideoInfo(
                                        id = System.currentTimeMillis().toString(),
                                        title = "${platform.displayName} Video",
                                        author = platform.displayName,
                                        thumbnailUrl = "",
                                        downloadUrl = downloadUrl,
                                        platform = platform,
                                        quality = "HD",
                                        isWatermarkFree = true,
                                        originalUrl = rawUrl
                                    )
                                )
                            }
                        } else if (status == "picker") {
                            val pickerArray = json.getAsJsonArray("picker")
                            if (pickerArray != null && pickerArray.size() > 0) {
                                val firstItem = pickerArray.get(0).asJsonObject
                                val downloadUrl = firstItem.get("url")?.asString ?: ""
                                val thumb = firstItem.get("thumb")?.asString ?: ""
                                if (downloadUrl.isNotBlank()) {
                                    Log.i(TAG, "[Cobalt Fallback] ÉXITO (Picker) -> Video URL: $downloadUrl")
                                    return Result.success(
                                        VideoInfo(
                                            id = System.currentTimeMillis().toString(),
                                            title = "${platform.displayName} Video",
                                            author = platform.displayName,
                                            thumbnailUrl = thumb,
                                            downloadUrl = downloadUrl,
                                            platform = platform,
                                            quality = "HD",
                                            isWatermarkFree = true,
                                            originalUrl = rawUrl
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Cobalt Fallback] Excepción: ${e.message}")
            }
        }

        return Result.failure(Exception("No se pudo obtener el video de ${platform.displayName}. Comprueba que el enlace sea público."))
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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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

                    if (playUrl.startsWith("/")) {
                        playUrl = "https://www.tikwm.com$playUrl"
                    }

                    val authorObj = data.getAsJsonObject("author")
                    val author = authorObj?.get("nickname")?.asString
                        ?: authorObj?.get("unique_id")?.asString
                        ?: "TikTok User"

                    val videoInfo = VideoInfo(
                        id = id,
                        title = title,
                        author = author,
                        thumbnailUrl = cover,
                        downloadUrl = playUrl,
                        platform = Platform.TIKTOK,
                        quality = "HD (Sin Marca de Agua)",
                        isWatermarkFree = true,
                        originalUrl = url
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

        val twitterUrl = "https://twitter.com/i/status/$statusId"
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
                    .header("User-Agent", desktopUserAgent)
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
                                        Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] VxTwitter ÉXITO -> $videoUrl")
                                        return Result.success(
                                            VideoInfo(
                                                id = statusId,
                                                title = text,
                                                author = userName,
                                                thumbnailUrl = thumb,
                                                downloadUrl = videoUrl,
                                                platform = Platform.TWITTER,
                                                quality = "HD",
                                                isWatermarkFree = true,
                                                originalUrl = url
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

        // 2. FxTwitter API
        val fxEndpoints = listOf(
            "https://api.fxtwitter.com/status/$statusId",
            "https://api.fxtwitter.com/i/status/$statusId"
        )

        for (fxUrl in fxEndpoints) {
            try {
                Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] Intentando FxTwitter API: $fxUrl")
                val request = Request.Builder()
                    .url(fxUrl)
                    .header("User-Agent", desktopUserAgent)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] FxTwitter HTTP Code: ${response.code}")
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.isNotBlank() && body.startsWith("{")) {
                            val json = JsonParser.parseString(body).asJsonObject
                            val tweet = json.getAsJsonObject("tweet")
                            if (tweet != null) {
                                val text = tweet.get("text")?.asString ?: "Twitter / X Video"
                                val authorObj = tweet.getAsJsonObject("author")
                                val author = authorObj?.get("name")?.asString ?: "Twitter User"
                                val mediaObj = tweet.getAsJsonObject("media")
                                val videos = mediaObj?.getAsJsonArray("videos")
                                if (videos != null && videos.size() > 0) {
                                    val video = videos.get(0).asJsonObject
                                    val videoUrl = video.get("url")?.asString ?: ""
                                    val thumb = video.get("thumbnail_url")?.asString ?: ""
                                    if (videoUrl.isNotBlank()) {
                                        Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] FxTwitter ÉXITO -> $videoUrl")
                                        return Result.success(
                                            VideoInfo(
                                                id = statusId,
                                                title = text,
                                                author = author,
                                                thumbnailUrl = thumb,
                                                downloadUrl = videoUrl,
                                                platform = Platform.TWITTER,
                                                quality = "HD",
                                                isWatermarkFree = true,
                                                originalUrl = url
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Excepción en FxTwitter: ${e.message}")
            }
        }

        // 3. Publer Media API
        try {
            val jsonPayload = JsonObject().apply {
                addProperty("url", twitterUrl)
            }.toString()

            Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] Intentando Publer API...")
            val request = Request.Builder()
                .url("https://publer.io/api/v1/media/download")
                .header("User-Agent", desktopUserAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", "https://publer.io")
                .header("Referer", "https://publer.io/")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] Publer HTTP Code: ${response.code}")
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isNotBlank() && bodyString.startsWith("{")) {
                        val json = JsonParser.parseString(bodyString).asJsonObject
                        val path = json.get("path")?.asString
                            ?: json.get("url")?.asString
                            ?: json.getAsJsonArray("payload")?.get(0)?.asJsonObject?.get("path")?.asString ?: ""

                        if (path.isNotBlank() && path.startsWith("http")) {
                            Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] Publer ÉXITO -> $path")
                            return Result.success(
                                VideoInfo(
                                    id = statusId,
                                    title = "Twitter / X Video",
                                    author = "Twitter User",
                                    thumbnailUrl = "",
                                    downloadUrl = path,
                                    platform = Platform.TWITTER,
                                    quality = "HD",
                                    isWatermarkFree = true,
                                    originalUrl = url
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Excepción Publer: ${e.message}")
        }

        return Result.failure(Exception("No se pudo obtener el video de Twitter/X."))
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE YOUTUBE (Invidious REST API + Cobalt)
// ============================================================================
class YouTubeExtractor(private val client: OkHttpClient) : PlatformExtractor {
    override val platform = Platform.YOUTUBE

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:v=|/videos/|embed/|shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^([A-Za-z0-9_-]{11})$")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    override suspend fun extract(url: String): Result<VideoInfo> {
        val videoId = extractVideoId(url)
        Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] Procesando URL: $url | Video ID: $videoId")

        if (!videoId.isNullOrBlank()) {
            val invidiousInstances = listOf(
                "https://inv.tux.pizza/api/v1/videos/$videoId",
                "https://vid.puffyan.us/api/v1/videos/$videoId",
                "https://invidious.drgns.space/api/v1/videos/$videoId"
            )

            for (apiUrl in invidiousInstances) {
                try {
                    Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] Consultado Invidious API: $apiUrl")
                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()

                    client.newCall(request).execute().use { response ->
                        Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] HTTP Code: ${response.code}")
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            if (body.isNotBlank() && body.startsWith("{")) {
                                val json = JsonParser.parseString(body).asJsonObject
                                val title = json.get("title")?.asString ?: "YouTube Video"
                                val author = json.get("author")?.asString ?: "YouTube Channel"

                                var thumbnailUrl = ""
                                val thumbs = json.getAsJsonArray("videoThumbnails")
                                if (thumbs != null && thumbs.size() > 0) {
                                    thumbnailUrl = thumbs.get(0).asJsonObject.get("url")?.asString ?: ""
                                }

                                val formatStreams = json.getAsJsonArray("formatStreams")
                                if (formatStreams != null && formatStreams.size() > 0) {
                                    for (i in formatStreams.size() - 1 downTo 0) {
                                        val stream = formatStreams.get(i).asJsonObject
                                        val videoUrl = stream.get("url")?.asString ?: ""
                                        val quality = stream.get("qualityLabel")?.asString ?: "720p"

                                        if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                                            Log.i(VideoExtractorRepository.TAG, "[YouTubeExtractor] ÉXITO -> $videoUrl")
                                            return Result.success(
                                                VideoInfo(
                                                    id = videoId,
                                                    title = title,
                                                    author = author,
                                                    thumbnailUrl = thumbnailUrl,
                                                    downloadUrl = videoUrl,
                                                    platform = Platform.YOUTUBE,
                                                    quality = quality,
                                                    isWatermarkFree = true,
                                                    originalUrl = url
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(VideoExtractorRepository.TAG, "[YouTubeExtractor] Excepción en Invidious: ${e.message}")
                }
            }
        }
        return Result.failure(Exception("No se pudo obtener el stream de YouTube."))
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE INSTAGRAM (Publer API + FastDL V3 + InDown CSRF + VxTagram)
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
                .header("User-Agent", desktopUserAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", "https://publer.io")
                .header("Referer", "https://publer.io/")
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
                            Log.i(VideoExtractorRepository.TAG, "[InstagramExtractor] Publer ÉXITO -> $path")
                            return Result.success(
                                VideoInfo(
                                    id = shortcode ?: System.currentTimeMillis().toString(),
                                    title = "Instagram Reel",
                                    author = "Instagram User",
                                    thumbnailUrl = "",
                                    downloadUrl = path,
                                    platform = Platform.INSTAGRAM,
                                    quality = "HD",
                                    isWatermarkFree = true,
                                    originalUrl = url
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
                .header("User-Agent", desktopUserAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", "https://fastdl.app")
                .header("Referer", "https://fastdl.app/")
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
                                Log.i(VideoExtractorRepository.TAG, "[InstagramExtractor] FastDL ÉXITO -> $firstUrl")
                                return Result.success(
                                    VideoInfo(
                                        id = shortcode ?: System.currentTimeMillis().toString(),
                                        title = "Instagram Reel",
                                        author = "Instagram User",
                                        thumbnailUrl = "",
                                        downloadUrl = firstUrl,
                                        platform = Platform.INSTAGRAM,
                                        quality = "HD",
                                        isWatermarkFree = true,
                                        originalUrl = url
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
// EXTRACTOR MODULAR DE FACEBOOK (Publer API + MBasic HTML + FDown)
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
                .header("User-Agent", desktopUserAgent)
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
                .header("User-Agent", desktopUserAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", "https://publer.io")
                .header("Referer", "https://publer.io/")
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
                            Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] Publer ÉXITO -> $path")
                            return Result.success(
                                VideoInfo(
                                    id = System.currentTimeMillis().toString(),
                                    title = "Facebook Video",
                                    author = "Facebook User",
                                    thumbnailUrl = "",
                                    downloadUrl = path,
                                    platform = Platform.FACEBOOK,
                                    quality = "HD",
                                    isWatermarkFree = true,
                                    originalUrl = url
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
                .header("User-Agent", mobileUserAgent)
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
                                        originalUrl = url
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
