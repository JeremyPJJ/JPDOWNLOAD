package com.example.videodownload.data.repository

import android.content.Context
import android.util.Log
import com.example.videodownload.data.model.DownloadOption
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.example.videodownload.utils.FacebookCookieManager
import com.example.videodownload.utils.InstagramCookieManager
import com.example.videodownload.utils.YouTubeCookieManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URLDecoder
import java.net.URLEncoder
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

class VideoExtractorRepository(private val context: Context) {

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
            TwitterExtractor(client, context),
            InstagramExtractor(context),
            FacebookExtractor(client, context),
            YouTubeExtractor(client, context)
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
// EXTRACTOR MODULAR DE TWITTER / X (FxTwitter + Syndication + yt-dlp + VxTwitter)
// ============================================================================
class TwitterExtractor(
    private val client: OkHttpClient,
    private val context: Context
) : PlatformExtractor {
    override val platform = Platform.TWITTER

    companion object {
        private const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36"
    }

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("twitter.com") || lower.contains("x.com")
    }

    private fun extractStatusId(url: String): String? {
        val statusMatcher = Pattern.compile("status/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url)
        return if (statusMatcher.find()) statusMatcher.group(1) else null
    }

    private fun normalizeTwitterUrl(rawUrl: String): String {
        val statusId = extractStatusId(rawUrl)
        return if (!statusId.isNullOrBlank()) {
            "https://twitter.com/i/status/$statusId"
        } else {
            rawUrl.split("?")[0].replace("x.com", "twitter.com").trim()
        }
    }

    override suspend fun extract(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val statusId = extractStatusId(url)
        val normalizedUrl = normalizeTwitterUrl(url)

        Log.d(VideoExtractorRepository.TAG, "==================================================")
        Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] URL Recibida: '$url'")
        Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] URL Normalizada: '$normalizedUrl' | Status ID: '$statusId'")

        if (statusId.isNullOrBlank()) {
            return@withContext Result.failure(Exception("No se pudo extraer el ID del Tweet de la URL ingresada."))
        }

        var isTweetFoundNoMedia = false

        // --------------------------------------------------------------------
        // MÉTODOS 1: FxTwitter API (Prioridad Alta - Devuelve JSON completo 200 OK)
        // --------------------------------------------------------------------
        try {
            val fxUrl = "https://api.fxtwitter.com/status/$statusId"
            Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] MÉTODOS 1 -> FxTwitter API: $fxUrl")

            val request = Request.Builder()
                .url(fxUrl)
                .addHeader("User-Agent", MOBILE_USER_AGENT)
                .addHeader("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                val bodyString = response.body?.string() ?: ""

                Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] FxTwitter HTTP Code: $responseCode")

                if (response.isSuccessful && bodyString.startsWith("{")) {
                    val json = JsonParser.parseString(bodyString).asJsonObject
                    if (json.has("tweet")) {
                        val tweetObj = json.getAsJsonObject("tweet")
                        val text = tweetObj.get("text")?.asString?.takeIf { it.isNotBlank() } ?: "Twitter / X Video"
                        val authorObj = if (tweetObj.has("author")) tweetObj.getAsJsonObject("author") else null
                        val authorName = authorObj?.get("name")?.asString ?: authorObj?.get("screen_name")?.asString ?: "Twitter User"

                        val mediaObj = if (tweetObj.has("media")) tweetObj.getAsJsonObject("media") else null
                        val quoteMediaObj = if (tweetObj.has("quote") && tweetObj.getAsJsonObject("quote").has("media")) {
                            tweetObj.getAsJsonObject("quote").getAsJsonObject("media")
                        } else null

                        Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] FxTwitter JSON Completo 'tweet.media': $mediaObj")

                        // Extracción de miniatura de alta calidad en FxTwitter
                        var fxThumbnail = ""
                        if (mediaObj != null) {
                            if (mediaObj.has("videos") && mediaObj.getAsJsonArray("videos").size() > 0) {
                                val v0 = mediaObj.getAsJsonArray("videos").get(0).asJsonObject
                                fxThumbnail = v0.get("thumbnail_url")?.asString
                                    ?: v0.get("poster")?.asString ?: ""
                            }
                            if (fxThumbnail.isBlank() && mediaObj.has("photos") && mediaObj.getAsJsonArray("photos").size() > 0) {
                                fxThumbnail = mediaObj.getAsJsonArray("photos").get(0).asJsonObject.get("url")?.asString ?: ""
                            }
                        }
                        if (fxThumbnail.isBlank() && quoteMediaObj != null) {
                            if (quoteMediaObj.has("videos") && quoteMediaObj.getAsJsonArray("videos").size() > 0) {
                                val v0 = quoteMediaObj.getAsJsonArray("videos").get(0).asJsonObject
                                fxThumbnail = v0.get("thumbnail_url")?.asString ?: ""
                            }
                        }

                        val optionsList = mutableListOf<DownloadOption>()

                        fun parseMediaObject(mObj: JsonObject?) {
                            if (mObj == null) return
                            if (mObj.has("videos")) {
                                val videos = mObj.getAsJsonArray("videos")
                                if (videos != null && videos.size() > 0) {
                                    for (i in 0 until videos.size()) {
                                        val vid = videos.get(i).asJsonObject
                                        val vUrl = vid.get("url")?.asString ?: ""
                                        val height = if (vid.has("height") && !vid.get("height").isJsonNull) vid.get("height").asInt else 0
                                        if (vUrl.isNotBlank() && vUrl.startsWith("http")) {
                                            val label = if (height > 0) "${height}p" else "HD"
                                            if (optionsList.none { it.quality == label }) {
                                                optionsList.add(
                                                    DownloadOption("Video $label", vUrl, isAudio = false, quality = label, extension = "mp4")
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (optionsList.isEmpty() && mObj.has("all")) {
                                val allMedia = mObj.getAsJsonArray("all")
                                if (allMedia != null && allMedia.size() > 0) {
                                    for (i in 0 until allMedia.size()) {
                                        val item = allMedia.get(i).asJsonObject
                                        val type = item.get("type")?.asString ?: ""
                                        val itemUrl = item.get("url")?.asString ?: ""
                                        if (itemUrl.isNotBlank() && (type == "video" || type == "gif" || itemUrl.contains(".mp4"))) {
                                            optionsList.add(
                                                DownloadOption("Video MP4", itemUrl, isAudio = false, quality = "HD", extension = "mp4")
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        parseMediaObject(mediaObj)
                        parseMediaObject(quoteMediaObj)

                        if (optionsList.isNotEmpty()) {
                            val bestUrl = optionsList[0].downloadUrl
                            optionsList.add(DownloadOption("Audio MP3", bestUrl, isAudio = true, quality = "MP3", extension = "mp3"))

                            Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] FxTwitter ÉXITO -> $bestUrl (Thumb: $fxThumbnail)")
                            return@withContext Result.success(
                                VideoInfo(
                                    id = statusId,
                                    title = text,
                                    author = authorName,
                                    thumbnailUrl = fxThumbnail,
                                    downloadUrl = bestUrl,
                                    platform = Platform.TWITTER,
                                    quality = optionsList[0].quality,
                                    isWatermarkFree = true,
                                    originalUrl = url,
                                    options = optionsList
                                )
                            )
                        } else {
                            Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] FxTwitter respondió 200 OK pero el JSON no contiene ninguna pista de video.")
                            isTweetFoundNoMedia = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Excepción FxTwitter API: ${e.message}")
        }

        // --------------------------------------------------------------------
        // MÉTODOS 2: Twitter Official Syndication API (`cdn.syndication.twimg.com`)
        // --------------------------------------------------------------------
        try {
            val syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=x"
            Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] MÉTODOS 2 -> Twitter Syndication API: $syndicationUrl")

            val request = Request.Builder()
                .url(syndicationUrl)
                .addHeader("User-Agent", MOBILE_USER_AGENT)
                .addHeader("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                val bodyString = response.body?.string() ?: ""

                Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] Syndication API HTTP Code: $responseCode")

                if (response.isSuccessful && bodyString.startsWith("{")) {
                    val json = JsonParser.parseString(bodyString).asJsonObject
                    val text = json.get("text")?.asString?.takeIf { it.isNotBlank() } ?: "Twitter / X Video"
                    val userObj = if (json.has("user")) json.getAsJsonObject("user") else null
                    val authorName = userObj?.get("name")?.asString ?: userObj?.get("screen_name")?.asString ?: "Twitter User"

                    var syndicationThumbnail = ""
                    if (json.has("mediaDetails")) {
                        val mediaArr = json.getAsJsonArray("mediaDetails")
                        if (mediaArr != null && mediaArr.size() > 0) {
                            syndicationThumbnail = mediaArr.get(0).asJsonObject.get("media_url_https")?.asString ?: ""
                        }
                    }

                    val optionsList = mutableListOf<DownloadOption>()

                    fun parseSyndicationMedia(mediaArr: JsonArray?) {
                        if (mediaArr == null) return
                        for (i in 0 until mediaArr.size()) {
                            val media = mediaArr.get(i).asJsonObject
                            val type = media.get("type")?.asString ?: ""
                            if ((type == "video" || type == "animated_gif") && media.has("video_info")) {
                                val videoInfoObj = media.getAsJsonObject("video_info")
                                if (videoInfoObj.has("variants")) {
                                    val variants = videoInfoObj.getAsJsonArray("variants")
                                    val mp4Variants = mutableListOf<JsonObject>()

                                    for (j in 0 until variants.size()) {
                                        val varObj = variants.get(j).asJsonObject
                                        val contentType = varObj.get("content_type")?.asString ?: ""
                                        if (contentType == "video/mp4" && varObj.has("url")) {
                                            mp4Variants.add(varObj)
                                        }
                                    }

                                    mp4Variants.sortByDescending {
                                        if (it.has("bitrate") && !it.get("bitrate").isJsonNull) it.get("bitrate").asLong else 0L
                                    }

                                    for (vObj in mp4Variants) {
                                        val vUrl = vObj.get("url")?.asString ?: ""
                                        val bitrate = if (vObj.has("bitrate") && !vObj.get("bitrate").isJsonNull) vObj.get("bitrate").asLong else 0L
                                        val qualityLabel = when {
                                            bitrate > 2000000 -> "1080p Full HD"
                                            bitrate > 800000 -> "720p HD"
                                            bitrate > 300000 -> "480p SD"
                                            else -> "SD"
                                        }
                                        if (vUrl.isNotBlank() && optionsList.none { it.quality == qualityLabel }) {
                                            optionsList.add(
                                                DownloadOption(qualityLabel, vUrl, isAudio = false, quality = qualityLabel, extension = "mp4")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (json.has("mediaDetails")) {
                        parseSyndicationMedia(json.getAsJsonArray("mediaDetails"))
                    }
                    if (json.has("quoted_tweet") && json.getAsJsonObject("quoted_tweet").has("mediaDetails")) {
                        parseSyndicationMedia(json.getAsJsonObject("quoted_tweet").getAsJsonArray("mediaDetails"))
                    }

                    if (optionsList.isNotEmpty()) {
                        val bestUrl = optionsList[0].downloadUrl
                        optionsList.add(DownloadOption("Audio MP3", bestUrl, isAudio = true, quality = "MP3", extension = "mp3"))

                        Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] Syndication API ÉXITO -> $bestUrl (Thumb: $syndicationThumbnail)")
                        return@withContext Result.success(
                            VideoInfo(
                                id = statusId,
                                title = text,
                                author = authorName,
                                thumbnailUrl = syndicationThumbnail,
                                downloadUrl = bestUrl,
                                platform = Platform.TWITTER,
                                quality = optionsList[0].quality,
                                isWatermarkFree = true,
                                originalUrl = url,
                                options = optionsList
                            )
                        )
                    } else {
                        Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Syndication API respondió 200 OK pero sin objetos de video_info.")
                        isTweetFoundNoMedia = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Excepción Syndication API: ${e.message}")
        }

        // --------------------------------------------------------------------
        // MÉTODOS 3: Native Local yt-dlp Executable Engine
        // --------------------------------------------------------------------
        try {
            Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] MÉTODOS 3 -> Native yt-dlp para '$normalizedUrl'...")
            val request = YoutubeDLRequest(normalizedUrl)
            request.addOption("--dump-json")
            request.addOption("--no-playlist")
            request.addOption("--user-agent", MOBILE_USER_AGENT)

            val response = YoutubeDL.getInstance().execute(request)
            val outJson = response.out ?: ""

            if (outJson.isNotBlank() && outJson.startsWith("{")) {
                val json = JsonParser.parseString(outJson).asJsonObject

                var mainUrl = json.get("url")?.asString ?: ""
                val title = json.get("title")?.asString?.takeIf { it.isNotBlank() }
                    ?: json.get("description")?.asString?.takeIf { it.isNotBlank() }
                    ?: "Twitter / X Video"
                val author = json.get("uploader")?.asString ?: json.get("uploader_id")?.asString ?: "Twitter User"
                val thumb = json.get("thumbnail")?.asString ?: ""

                val optionsList = mutableListOf<DownloadOption>()

                if (json.has("formats")) {
                    val formats = json.getAsJsonArray("formats")
                    if (formats != null && formats.size() > 0) {
                        for (i in 0 until formats.size()) {
                            val fmt = formats.get(i).asJsonObject
                            val fmtUrl = fmt.get("url")?.asString ?: ""
                            val ext = fmt.get("ext")?.asString ?: "mp4"
                            val height = if (fmt.has("height") && !fmt.get("height").isJsonNull) fmt.get("height").asInt else 0

                            if (fmtUrl.isNotBlank() && (fmtUrl.contains("twimg.com") || fmtUrl.contains(".mp4"))) {
                                val qualityLabel = if (height > 0) "${height}p" else "HD"
                                if (optionsList.none { it.quality == qualityLabel }) {
                                    optionsList.add(
                                        DownloadOption(
                                            label = "Video $qualityLabel",
                                            downloadUrl = fmtUrl,
                                            isAudio = false,
                                            quality = qualityLabel,
                                            extension = ext
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (mainUrl.isBlank() && optionsList.isNotEmpty()) {
                    mainUrl = optionsList[0].downloadUrl
                }

                if (optionsList.isEmpty() && mainUrl.isNotBlank()) {
                    optionsList.add(DownloadOption("Full HD (1080p)", mainUrl, isAudio = false, quality = "1080p", extension = "mp4"))
                }

                if (mainUrl.isNotBlank()) {
                    optionsList.add(DownloadOption("Audio MP3", mainUrl, isAudio = true, quality = "MP3", extension = "mp3"))
                }

                if (mainUrl.isNotBlank() && mainUrl.startsWith("http")) {
                    Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] Native yt-dlp ÉXITO -> $mainUrl (Thumb: $thumb)")
                    return@withContext Result.success(
                        VideoInfo(
                            id = statusId,
                            title = title,
                            author = author,
                            thumbnailUrl = thumb,
                            downloadUrl = mainUrl,
                            platform = Platform.TWITTER,
                            quality = optionsList.firstOrNull()?.quality ?: "HD",
                            isWatermarkFree = true,
                            originalUrl = url,
                            options = optionsList
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Excepción Native yt-dlp: ${e.message}")
        }

        // --------------------------------------------------------------------
        // MÉTODOS 4: VxTwitter API
        // --------------------------------------------------------------------
        try {
            val vxUrl = "https://api.vxtwitter.com/status/$statusId"
            Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] MÉTODOS 4 -> VxTwitter API: $vxUrl")

            val request = Request.Builder()
                .url(vxUrl)
                .addHeader("User-Agent", MOBILE_USER_AGENT)
                .addHeader("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                val bodyString = response.body?.string() ?: ""

                Log.d(VideoExtractorRepository.TAG, "[TwitterExtractor] VxTwitter HTTP Code: $responseCode")

                if (response.isSuccessful && bodyString.startsWith("{")) {
                    val json = JsonParser.parseString(bodyString).asJsonObject
                    val text = json.get("text")?.asString?.takeIf { it.isNotBlank() } ?: "Twitter / X Video"
                    val userName = json.get("user_name")?.asString ?: json.get("user_screen_name")?.asString ?: "Twitter User"

                    var mediaUrl: String? = null
                    var vxThumbnail = ""
                    val mediaExtended = json.getAsJsonArray("media_extended")
                    if (mediaExtended != null && mediaExtended.size() > 0) {
                        for (i in 0 until mediaExtended.size()) {
                            val media = mediaExtended.get(i).asJsonObject
                            val mUrl = media.get("url")?.asString ?: ""
                            val mType = media.get("type")?.asString ?: ""
                            if (mUrl.isNotBlank() && (mType == "video" || mType == "gif" || mUrl.contains(".mp4"))) {
                                mediaUrl = mUrl
                                vxThumbnail = media.get("thumbnail_url")?.asString ?: ""
                                break
                            }
                        }
                    }

                    if (!mediaUrl.isNullOrBlank() && mediaUrl.startsWith("http")) {
                        val options = listOf(
                            DownloadOption("Full HD (1080p)", mediaUrl, isAudio = false, quality = "1080p", extension = "mp4"),
                            DownloadOption("HD (720p)", mediaUrl, isAudio = false, quality = "720p", extension = "mp4"),
                            DownloadOption("Audio MP3", mediaUrl, isAudio = true, quality = "MP3", extension = "mp3")
                        )

                        Log.i(VideoExtractorRepository.TAG, "[TwitterExtractor] VxTwitter ÉXITO -> $mediaUrl (Thumb: $vxThumbnail)")
                        return@withContext Result.success(
                            VideoInfo(
                                id = statusId,
                                title = text,
                                author = userName,
                                thumbnailUrl = vxThumbnail,
                                downloadUrl = mediaUrl,
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
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[TwitterExtractor] Excepción VxTwitter API: ${e.message}")
        }

        val finalErrorMsg = if (isTweetFoundNoMedia) {
            "El Tweet no contiene ningún video o GIF para descargar."
        } else {
            "No se pudo extraer el video de Twitter / X. Verifica que la publicación sea pública y contenga un video."
        }

        return@withContext Result.failure(Exception(finalErrorMsg))
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE YOUTUBE (1. NewPipeExtractor v0.26.5 + 2. Native yt-dlp Ajustado)
// ============================================================================
class NewPipeOkHttpDownloader(
    private val client: OkHttpClient,
    private val context: Context
) : Downloader() {

    private val cookieMap = ConcurrentHashMap<String, String>()

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "en-US,en;q=0.9,es-ES;q=0.8")

        val googleCookieHeader = YouTubeCookieManager.getCookieHeader(context)
        if (!googleCookieHeader.isNullOrBlank()) {
            requestBuilder.addHeader("Cookie", googleCookieHeader)
        }

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

            return Response(responseCode, responseMessage, responseHeaders, responseBody, url)
        }
    }
}

class YouTubeExtractor(
    private val client: OkHttpClient,
    private val context: Context
) : PlatformExtractor {
    override val platform = Platform.YOUTUBE

    companion object {
        const val NEWPIPE_VERSION = "v0.26.5"
        private const val BACKOFF_CACHE_MS = 300_000L // 5 minutos de cache tras bot-check
        private val blockedVideoIds = ConcurrentHashMap<String, Long>()
        private const val USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    init {
        try {
            NewPipe.init(NewPipeOkHttpDownloader(client, context))
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
        Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] Raw URL: '$url' | Video ID: '$videoId'")

        if (videoId.isNullOrBlank()) {
            return@withContext Result.failure(Exception("No se pudo extraer el ID del video de YouTube."))
        }

        val hasCookies = YouTubeCookieManager.hasCookies(context)

        // Comprobar Cache de Backoff tras bot-check
        val lastBlockedTime = blockedVideoIds[videoId] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < BACKOFF_CACHE_MS) {
            val remainingMin = ((BACKOFF_CACHE_MS - (now - lastBlockedTime)) / 60000L) + 1
            Log.w(VideoExtractorRepository.TAG, "[YouTubeExtractor] Video $videoId está en cache de bloqueo por $remainingMin min más.")
            val msg = if (hasCookies) {
                "La sesión expiró o fue rechazada. Vuelve a iniciar sesión en Google."
            } else {
                "YouTube pide verificación. Inicia sesión con Google (cuenta secundaria) o cambia de red."
            }
            return@withContext Result.failure(Exception(msg))
        }

        var isBotCheckError = false

        // --------------------------------------------------------------------
        // MÉTODOS 1: NewPipeExtractor v0.26.5
        // --------------------------------------------------------------------
        try {
            Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] MÉTODOS 1 -> Ejecutando NewPipeExtractor $NEWPIPE_VERSION para '$canonicalYoutubeUrl'...")
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, canonicalYoutubeUrl)

            if (streamInfo != null) {
                val title = streamInfo.name ?: "YouTube Video"
                val uploader = streamInfo.uploaderName ?: "YouTube Channel"
                val thumb = streamInfo.thumbnails.firstOrNull()?.url ?: ""

                val optionsList = mutableListOf<DownloadOption>()
                val audioStreams = streamInfo.audioStreams
                val bestAudioUrl = audioStreams?.firstOrNull()?.content

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

                if (audioStreams != null && audioStreams.isNotEmpty()) {
                    val firstAudio = audioStreams[0]
                    val audioUrl = firstAudio.content
                    if (!audioUrl.isNullOrBlank()) {
                        optionsList.add(
                            DownloadOption(
                                label = "Audio MP3",
                                downloadUrl = audioUrl,
                                isAudio = true,
                                quality = "MP3",
                                extension = "m4a"
                            )
                        )
                    }
                }

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
                    Log.i(VideoExtractorRepository.TAG, "[YouTubeExtractor] NewPipeExtractor ÉXITO -> ${optionsList.size} calidades encontradas")
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
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.w(VideoExtractorRepository.TAG, "[YouTubeExtractor] Excepción NewPipeExtractor: $msg (${e.javaClass.simpleName})")
            if (msg.contains("LOGIN_REQUIRED", ignoreCase = true) || msg.contains("Sign in to confirm", ignoreCase = true) || msg.contains("bot", ignoreCase = true)) {
                isBotCheckError = true
            }
        }

        // --------------------------------------------------------------------
        // MÉTODOS 2: Native yt-dlp Executable Engine
        // --------------------------------------------------------------------
        try {
            Log.d(VideoExtractorRepository.TAG, "[YouTubeExtractor] MÉTODOS 2 -> Native yt-dlp para '$canonicalYoutubeUrl'...")
            val request = YoutubeDLRequest(canonicalYoutubeUrl)
            request.addOption("--dump-json")
            request.addOption("--no-playlist")
            request.addOption("--user-agent", USER_AGENT_MOBILE)
            request.addOption("--extractor-retries", "1")
            request.addOption("--retry-sleep", "5")
            request.addOption("--no-check-certificates")

            if (hasCookies) {
                val cookieFile = YouTubeCookieManager.getCookieFile(context)
                Log.i(VideoExtractorRepository.TAG, "[YouTubeExtractor] Modo Autenticado -> usando cookies y player_client=default,mweb")
                request.addOption("--cookies", cookieFile.absolutePath)
                request.addOption("--extractor-args", "youtube:player_client=default,mweb")
            } else {
                Log.i(VideoExtractorRepository.TAG, "[YouTubeExtractor] Modo Anónimo -> usando player_client=android,ios")
                request.addOption("--extractor-args", "youtube:player_client=android,ios")
            }

            val response = YoutubeDL.getInstance().execute(request)
            val outJson = response.out ?: ""

            if (outJson.isNotBlank() && outJson.startsWith("{")) {
                val json = JsonParser.parseString(outJson).asJsonObject

                var videoUrl = json.get("url")?.asString ?: ""
                val title = json.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "YouTube Video"
                val author = json.get("uploader")?.asString ?: json.get("uploader_id")?.asString ?: "YouTube Channel"
                val thumb = json.get("thumbnail")?.asString ?: ""

                val optionsList = mutableListOf<DownloadOption>()

                if (json.has("formats")) {
                    val formats = json.getAsJsonArray("formats")
                    if (formats != null && formats.size() > 0) {
                        for (i in 0 until formats.size()) {
                            val fmt = formats.get(i).asJsonObject
                            val fmtUrl = fmt.get("url")?.asString ?: ""
                            val height = if (fmt.has("height") && !fmt.get("height").isJsonNull) fmt.get("height").asInt else 0
                            val vcodec = fmt.get("vcodec")?.asString ?: ""

                            if (fmtUrl.isNotBlank() && fmtUrl.startsWith("http") && vcodec != "none") {
                                val label = if (height > 0) "${height}p HD" else "HD"
                                if (optionsList.none { it.quality == label }) {
                                    optionsList.add(DownloadOption("Video $label", fmtUrl, isAudio = false, quality = label, extension = "mp4"))
                                }
                            }
                        }
                    }
                }

                if (videoUrl.isBlank() && optionsList.isNotEmpty()) {
                    videoUrl = optionsList[0].downloadUrl
                }

                if (optionsList.isEmpty() && videoUrl.isNotBlank()) {
                    optionsList.add(DownloadOption("Full HD (1080p)", videoUrl, isAudio = false, quality = "1080p", extension = "mp4"))
                }

                if (videoUrl.isNotBlank()) {
                    optionsList.add(DownloadOption("Audio MP3", videoUrl, isAudio = true, quality = "MP3", extension = "mp3"))
                }

                if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                    Log.i(VideoExtractorRepository.TAG, "[YouTubeExtractor] Native yt-dlp ÉXITO -> $videoUrl (${optionsList.size} opciones)")
                    return@withContext Result.success(
                        VideoInfo(
                            id = videoId,
                            title = title,
                            author = author,
                            thumbnailUrl = thumb,
                            downloadUrl = videoUrl,
                            platform = Platform.YOUTUBE,
                            quality = optionsList[0].quality,
                            isWatermarkFree = true,
                            originalUrl = url,
                            options = optionsList
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val fullErr = e.message ?: ""
            Log.w(VideoExtractorRepository.TAG, "[YouTubeExtractor] Excepción Native yt-dlp: $fullErr")
            if (fullErr.contains("Sign in to confirm", ignoreCase = true) || fullErr.contains("bot", ignoreCase = true) || fullErr.contains("Precondition check failed", ignoreCase = true)) {
                isBotCheckError = true
            }
        }

        if (isBotCheckError) {
            blockedVideoIds[videoId] = System.currentTimeMillis()
            val userMsg = if (hasCookies) {
                "La sesión expiró, vuelve a iniciar sesión."
            } else {
                "YouTube pide verificación. Inicia sesión con Google (cuenta secundaria) o cambia de red."
            }
            return@withContext Result.failure(Exception(userMsg))
        }

        return@withContext Result.failure(
            Exception("No se pudo obtener el video de YouTube. Intenta de nuevo en unos momentos.")
        )
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE INSTAGRAM (100% Local Ejecutable Nativo con youtubedl-android)
// ============================================================================
class InstagramExtractor(private val context: Context) : PlatformExtractor {
    override val platform = Platform.INSTAGRAM

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36"
    }

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

    override suspend fun extract(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val cleanUrl = url.split("?")[0].trim()
        val shortcode = extractShortcode(cleanUrl)
        val canonicalUrl = if (!shortcode.isNullOrBlank()) "https://www.instagram.com/reel/$shortcode/" else cleanUrl

        Log.d(VideoExtractorRepository.TAG, "==================================================")
        Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] Ejecutando Local Native yt-dlp Executable Engine...")
        Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] URL: '$canonicalUrl' | Shortcode: '$shortcode'")

        var isRetryAfterUpdate = false
        while (true) {
            try {
                val request = YoutubeDLRequest(canonicalUrl)
                request.addOption("--dump-json")
                request.addOption("--no-playlist")
                request.addOption("--user-agent", USER_AGENT)

                // Cargar cookies privadas del almacenamiento interno si el usuario inició sesión
                val cookieFile = InstagramCookieManager.getCookieFile(context)
                if (InstagramCookieManager.hasCookies(context)) {
                    Log.i(VideoExtractorRepository.TAG, "[InstagramExtractor] Cargando cookies de Instagram desde almacenamiento privado: ${cookieFile.absolutePath}")
                    request.addOption("--cookies", cookieFile.absolutePath)
                } else {
                    Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] No hay cookies privadas guardadas. Ejecutando extracción anónima.")
                }

                Log.d(VideoExtractorRepository.TAG, "[InstagramExtractor] Ejecutando YoutubeDL.getInstance().execute()...")
                val response = YoutubeDL.getInstance().execute(request)
                val outJson = response.out ?: ""

                if (outJson.isNotBlank() && outJson.startsWith("{")) {
                    val json = JsonParser.parseString(outJson).asJsonObject

                    var directVideoUrl = json.get("url")?.asString ?: ""
                    if (directVideoUrl.isBlank() && json.has("requested_formats")) {
                        val formats = json.getAsJsonArray("requested_formats")
                        if (formats != null && formats.size() > 0) {
                            directVideoUrl = formats.get(0).asJsonObject.get("url")?.asString ?: ""
                        }
                    }

                    val title = json.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "Instagram Reel"
                    val author = json.get("uploader")?.asString ?: json.get("uploader_id")?.asString ?: "Instagram User"
                    val thumb = json.get("thumbnail")?.asString ?: ""

                    if (directVideoUrl.isNotBlank() && directVideoUrl.startsWith("http")) {
                        val options = listOf(
                            DownloadOption("Full HD (1080p)", directVideoUrl, isAudio = false, quality = "1080p", extension = "mp4"),
                            DownloadOption("HD (720p)", directVideoUrl, isAudio = false, quality = "720p", extension = "mp4"),
                            DownloadOption("Audio MP3", directVideoUrl, isAudio = true, quality = "MP3", extension = "mp3")
                        )

                        Log.i(VideoExtractorRepository.TAG, "[InstagramExtractor] Native yt-dlp ÉXITO -> $directVideoUrl")
                        return@withContext Result.success(
                            VideoInfo(
                                id = shortcode ?: System.currentTimeMillis().toString(),
                                title = title,
                                author = author,
                                thumbnailUrl = thumb,
                                downloadUrl = directVideoUrl,
                                platform = Platform.INSTAGRAM,
                                quality = "1080p Full HD",
                                isWatermarkFree = true,
                                originalUrl = url,
                                options = options
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: ""
                Log.e(VideoExtractorRepository.TAG, "[InstagramExtractor] Excepción Native yt-dlp: $errorMsg", e)

                // Si no hemos intentado actualizar yt-dlp y es un error de extractor/firma
                if (!isRetryAfterUpdate && (errorMsg.contains("ExtractorError") || errorMsg.contains("signature") || errorMsg.contains("update") || errorMsg.contains("HTTP Error"))) {
                    isRetryAfterUpdate = true
                    try {
                        Log.w(VideoExtractorRepository.TAG, "[InstagramExtractor] Fallo de extractor. Intentando actualización automática de yt-dlp (YoutubeDL.updateYoutubeDL)...")
                        YoutubeDL.getInstance().updateYoutubeDL(context)
                        Log.i(VideoExtractorRepository.TAG, "[InstagramExtractor] yt-dlp actualizado con éxito. Reintentando extracción...")
                        continue
                    } catch (updateEx: Exception) {
                        Log.e(VideoExtractorRepository.TAG, "[InstagramExtractor] Error actualizando yt-dlp: ${updateEx.message}")
                    }
                }

                // Mapeo concreto de errores
                val userError = when {
                    errorMsg.contains("Private", ignoreCase = true) || errorMsg.contains("login", ignoreCase = true) ->
                        "El Reel o publicación de Instagram es privado o requiere inicio de sesión."
                    errorMsg.contains("404", ignoreCase = true) || errorMsg.contains("not found", ignoreCase = true) ->
                        "El Reel de Instagram fue eliminado o el enlace no es válido."
                    errorMsg.contains("429", ignoreCase = true) || errorMsg.contains("rate limit", ignoreCase = true) ->
                        "Límite de peticiones excedido en Instagram."
                    else ->
                        "Error de extracción en Instagram: ${e.localizedMessage}"
                }

                return@withContext Result.failure(Exception(userError))
            }

            break
        }

        return@withContext Result.failure(Exception("No se pudo extraer el Reel de Instagram."))
    }
}

// ============================================================================
// EXTRACTOR MODULAR DE FACEBOOK (Expansion + yt-dlp + Crawler Scraper + Embed Plugin)
// ============================================================================
class FacebookExtractor(
    private val client: OkHttpClient,
    private val context: Context
) : PlatformExtractor {
    override val platform = Platform.FACEBOOK

    companion object {
        private const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val CRAWLER_USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    }

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com")
    }

    private fun extractFacebookVideoId(html: String, finalUrl: String): String? {
        // 1. og:url / og:video
        val ogPattern = Pattern.compile("<meta\\s+property=[\"']og:(?:url|video(?::secure_url)?)[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        while (ogPattern.find()) {
            val ogContent = ogPattern.group(1) ?: ""
            val m = Pattern.compile("(?:reel|reels|videos|watch/?\\?v=)/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(ogContent)
            if (m.find()) return m.group(1)
        }

        // 2. canonical link
        val canPattern = Pattern.compile("<link\\s+rel=[\"']canonical[\"']\\s+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (canPattern.find()) {
            val canUrl = canPattern.group(1) ?: ""
            val m = Pattern.compile("(?:reel|reels|videos|watch/?\\?v=)/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(canUrl)
            if (m.find()) return m.group(1)
        }

        // 3. regex directa en JSON / HTML
        val patterns = listOf(
            Pattern.compile("[\"']video_id[\"']\\s*:\\s*[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[\"']top_level_post_id[\"']\\s*:\\s*[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/reel/(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/videos/(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("watch/\\?v=(\\d+)", Pattern.CASE_INSENSITIVE)
        )
        for (p in patterns) {
            val m = p.matcher(html)
            if (m.find()) return m.group(1)
        }

        // 4. regex en la URL final
        val urlMatcher = Pattern.compile("(?:reel|reels|videos|watch/?\\?v=)/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(finalUrl)
        if (urlMatcher.find()) return urlMatcher.group(1)

        return null
    }

    private fun expandFacebookUrl(rawUrl: String): Pair<String, String?> {
        Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] Expandiendo URL inicial: '$rawUrl'")

        val userAgentsToTry = listOf(
            MOBILE_USER_AGENT,
            DESKTOP_USER_AGENT,
            CRAWLER_USER_AGENT
        )

        for (ua in userAgentsToTry) {
            try {
                val request = Request.Builder()
                    .url(rawUrl)
                    .addHeader("User-Agent", ua)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Sec-Fetch-Mode", "navigate")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    val html = response.body?.string() ?: ""

                    Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] HTTP Code: ${response.code} (UA: ${ua.take(20)}...) | Tamaño: ${html.length} bytes | Final: '$finalUrl'")

                    val videoId = extractFacebookVideoId(html, finalUrl)
                    if (!videoId.isNullOrBlank()) {
                        val canonical = "https://www.facebook.com/reel/$videoId/"
                        Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] Video ID Extraído: '$videoId' | Canonical URL: '$canonical'")
                        return canonical to videoId
                    }
                }
            } catch (e: Exception) {
                Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Excepción expandiendo URL con UA '${ua.take(20)}': ${e.message}")
            }
        }

        val clean = rawUrl.split("?")[0].trim()
        return clean to null
    }

    private fun unescapeFacebookUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("\\u0025", "%")
            .replace("%3A", ":")
            .replace("%2F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun isRealVideoUrl(candidateUrl: String?): Boolean {
        if (candidateUrl.isNullOrBlank()) return false
        val lower = candidateUrl.lowercase()
        if (lower.contains("lookaside.fbsbx.com") || lower.contains("fbsbx.com/lookaside") || lower.contains("crawler/media")) return false
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp")) return false
        return lower.contains("video.fccp") || lower.contains("video.fcdn") || lower.contains("video.fb") || lower.contains("fbcdn.net") || lower.contains(".mp4")
    }

    private fun validateFacebookVideoUrl(candidateUrl: String, userAgent: String): Boolean {
        if (!isRealVideoUrl(candidateUrl)) return false
        try {
            val request = Request.Builder()
                .url(candidateUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Range", "bytes=0-1023")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L

                Log.d(VideoExtractorRepository.TAG, "[FacebookValidation] Check -> Code: $code | Content-Type: '$contentType' | Length: $contentLength")

                if ((code == 200 || code == 206) && (contentType.startsWith("video/") || contentType.contains("mp4") || contentType.contains("octet-stream"))) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(VideoExtractorRepository.TAG, "[FacebookValidation] Excepción validando URL candidatada: ${e.message}")
        }
        return false
    }

    override suspend fun extract(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        var (canonicalUrl, videoId) = expandFacebookUrl(url)
        Log.d(VideoExtractorRepository.TAG, "==================================================")
        Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] URL Original: '$url' | Canonical URL: '$canonicalUrl' | ID: '$videoId'")

        var lastError: Exception? = null

        // --------------------------------------------------------------------
        // MÉTODOS 1: Native Local yt-dlp Executable Engine
        // --------------------------------------------------------------------
        val targetUrlsYtDlp = mutableListOf<String>()
        if (!videoId.isNullOrBlank()) {
            targetUrlsYtDlp.add("https://www.facebook.com/reel/$videoId/")
            targetUrlsYtDlp.add("https://www.facebook.com/watch/?v=$videoId")
        }
        if (!targetUrlsYtDlp.contains(canonicalUrl)) targetUrlsYtDlp.add(canonicalUrl)
        if (!targetUrlsYtDlp.contains(url)) targetUrlsYtDlp.add(url)

        for (targetUrl in targetUrlsYtDlp) {
            try {
                Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] MÉTODOS 1 -> Native yt-dlp para '$targetUrl'...")
                val request = YoutubeDLRequest(targetUrl)
                request.addOption("--dump-json")
                request.addOption("--no-playlist")
                request.addOption("--user-agent", MOBILE_USER_AGENT)
                request.addOption("--no-check-certificates")

                val cookieFile = FacebookCookieManager.getCookieFile(context)
                if (FacebookCookieManager.hasCookies(context)) {
                    Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] Cargando cookies privadas de Facebook: ${cookieFile.absolutePath}")
                    request.addOption("--cookies", cookieFile.absolutePath)
                }

                val response = YoutubeDL.getInstance().execute(request)
                val outJson = response.out ?: ""

                if (outJson.isNotBlank() && outJson.startsWith("{")) {
                    val json = JsonParser.parseString(outJson).asJsonObject

                    var videoUrl = json.get("url")?.asString ?: ""
                    val title = json.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "Facebook Video"
                    val author = json.get("uploader")?.asString ?: json.get("uploader_id")?.asString ?: "Facebook User"
                    val thumb = json.get("thumbnail")?.asString ?: ""

                    val optionsList = mutableListOf<DownloadOption>()

                    if (json.has("formats")) {
                        val formats = json.getAsJsonArray("formats")
                        if (formats != null && formats.size() > 0) {
                            for (i in 0 until formats.size()) {
                                val fmt = formats.get(i).asJsonObject
                                val fmtUrl = fmt.get("url")?.asString ?: ""
                                val formatNote = fmt.get("format_note")?.asString ?: fmt.get("format_id")?.asString ?: ""
                                val height = if (fmt.has("height") && !fmt.get("height").isJsonNull) fmt.get("height").asInt else 0

                                if (fmtUrl.isNotBlank() && fmtUrl.startsWith("http") && isRealVideoUrl(fmtUrl)) {
                                    val label = when {
                                        formatNote.contains("hd", ignoreCase = true) || height >= 720 -> "HD (${if (height > 0) "${height}p" else "720p"})"
                                        else -> "SD (${if (height > 0) "${height}p" else "480p"})"
                                    }
                                    if (optionsList.none { it.quality == label }) {
                                        optionsList.add(DownloadOption("Video $label", fmtUrl, isAudio = false, quality = label, extension = "mp4"))
                                    }
                                }
                            }
                        }
                    }

                    if (videoUrl.isBlank() && optionsList.isNotEmpty()) {
                        videoUrl = optionsList[0].downloadUrl
                    }

                    if (optionsList.isEmpty() && videoUrl.isNotBlank() && isRealVideoUrl(videoUrl)) {
                        optionsList.add(DownloadOption("Full HD (1080p)", videoUrl, isAudio = false, quality = "1080p", extension = "mp4"))
                        optionsList.add(DownloadOption("HD (720p)", videoUrl, isAudio = false, quality = "720p", extension = "mp4"))
                    }

                    if (videoUrl.isNotBlank() && isRealVideoUrl(videoUrl)) {
                        optionsList.add(DownloadOption("Audio MP3", videoUrl, isAudio = true, quality = "MP3", extension = "mp3"))
                    }

                    if (videoUrl.isNotBlank() && isRealVideoUrl(videoUrl)) {
                        Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] Native yt-dlp ÉXITO -> $videoUrl (${optionsList.size} opciones)")
                        return@withContext Result.success(
                            VideoInfo(
                                id = videoId ?: System.currentTimeMillis().toString(),
                                title = title,
                                author = author,
                                thumbnailUrl = thumb,
                                downloadUrl = videoUrl,
                                platform = Platform.FACEBOOK,
                                quality = optionsList[0].quality,
                                isWatermarkFree = true,
                                originalUrl = url,
                                options = optionsList
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                val fullErr = e.message ?: ""
                Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Excepción Native yt-dlp: $fullErr")

                val idMatcher = Pattern.compile("\\[facebook\\]\\s*(\\d+):", Pattern.CASE_INSENSITIVE).matcher(fullErr)
                if (idMatcher.find()) {
                    val capturedId = idMatcher.group(1)
                    if (!capturedId.isNullOrBlank() && videoId.isNullOrBlank()) {
                        videoId = capturedId
                        canonicalUrl = "https://www.facebook.com/reel/$videoId/"
                        Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] ID Capturado desde error de yt-dlp: '$videoId' | Nueva Canonical: '$canonicalUrl'")
                    }
                }

                if (fullErr.contains("login", ignoreCase = true) || fullErr.contains("private", ignoreCase = true)) {
                    lastError = Exception("El video de Facebook es privado o requiere inicio de sesión.")
                }
            }
        }

        // --------------------------------------------------------------------
        // MÉTODOS 2: Facebook Crawler Scraper (`facebookexternalhit`)
        // --------------------------------------------------------------------
        if (!videoId.isNullOrBlank()) {
            val crawlerUrlsToScrape = listOf(
                "https://www.facebook.com/reel/$videoId/",
                "https://m.facebook.com/reel/$videoId/"
            )

            for (scrapeUrl in crawlerUrlsToScrape) {
                try {
                    Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] MÉTODOS 2 -> Facebook Crawler Scraper (facebookexternalhit): $scrapeUrl")
                    val request = Request.Builder()
                        .url(scrapeUrl)
                        .addHeader("User-Agent", CRAWLER_USER_AGENT)
                        .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .addHeader("Accept-Language", "en-US,en;q=0.9")
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        val responseCode = response.code
                        val finalScrapeUrl = response.request.url.toString()
                        val html = response.body?.string() ?: ""

                        Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] Crawler Scraper HTTP $responseCode | Tamaño: ${html.length} bytes | Final: '$finalScrapeUrl'")

                        val isLoginRequired = finalScrapeUrl.contains("/login") || html.contains("log in to continue", ignoreCase = true) || html.contains("You must log in", ignoreCase = true) || html.contains("This content isn't available", ignoreCase = true)
                        if (isLoginRequired) {
                            Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Muro de inicio de sesión detectado en Crawler Scraper: $scrapeUrl")
                            lastError = Exception("El video de Facebook es privado o requiere inicio de sesión.")
                        } else if (response.isSuccessful && html.isNotBlank()) {
                            var hdUrl: String? = null
                            var sdUrl: String? = null

                            val patterns = listOf(
                                Pattern.compile("[\"']og:video:secure_url[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                                Pattern.compile("[\"']og:video:url[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                                Pattern.compile("[\"']og:video[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                                Pattern.compile("[\"']browser_native_hd_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                                Pattern.compile("[\"']playable_url_quality_hd[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                                Pattern.compile("[\"']browser_native_sd_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                                Pattern.compile("[\"']playable_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                            )

                            for (p in patterns) {
                                val m = p.matcher(html)
                                if (m.find()) {
                                    val candidate = unescapeFacebookUrl(m.group(1))
                                    if (isRealVideoUrl(candidate)) {
                                        if (candidate!!.contains("hd") || candidate.contains("quality_hd")) {
                                            if (hdUrl == null) hdUrl = candidate
                                        } else {
                                            if (sdUrl == null) sdUrl = candidate
                                        }
                                    }
                                }
                            }

                            val bestMediaUrl = hdUrl ?: sdUrl
                            if (!bestMediaUrl.isNullOrBlank() && validateFacebookVideoUrl(bestMediaUrl, CRAWLER_USER_AGENT)) {
                                val options = mutableListOf<DownloadOption>()
                                if (!hdUrl.isNullOrBlank()) options.add(DownloadOption("Full HD (1080p)", hdUrl, isAudio = false, quality = "1080p", extension = "mp4"))
                                if (!sdUrl.isNullOrBlank()) options.add(DownloadOption("SD (480p)", sdUrl, isAudio = false, quality = "480p", extension = "mp4"))
                                options.add(DownloadOption("Audio MP3", bestMediaUrl, isAudio = true, quality = "MP3", extension = "mp3"))

                                Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] Crawler Scraper ÉXITO -> $bestMediaUrl")
                                return@withContext Result.success(
                                    VideoInfo(
                                        id = videoId,
                                        title = "Facebook Video",
                                        author = "Facebook User",
                                        thumbnailUrl = "",
                                        downloadUrl = bestMediaUrl,
                                        platform = Platform.FACEBOOK,
                                        quality = if (!hdUrl.isNullOrBlank()) "1080p Full HD" else "SD",
                                        isWatermarkFree = true,
                                        originalUrl = url,
                                        options = options
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Excepción Crawler Scraper ($scrapeUrl): ${e.message}")
                }
            }
        }

        // --------------------------------------------------------------------
        // MÉTODOS 3: Facebook Video Plugin Embed Scraper
        // --------------------------------------------------------------------
        if (!videoId.isNullOrBlank()) {
            try {
                val targetEmbed = "https://www.facebook.com/reel/$videoId/"
                val encodedTarget = URLEncoder.encode(targetEmbed, "UTF-8")
                val embedPluginUrl = "https://www.facebook.com/plugins/video.php?href=$encodedTarget&show_text=false"
                Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] MÉTODOS 3 -> Facebook Plugin Embed Scraper: $embedPluginUrl")

                val request = Request.Builder()
                    .url(embedPluginUrl)
                    .addHeader("User-Agent", CRAWLER_USER_AGENT)
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    val html = response.body?.string() ?: ""

                    Log.d(VideoExtractorRepository.TAG, "[FacebookExtractor] Embed Plugin HTTP Code: $responseCode | Tamaño: ${html.length} bytes")

                    if (response.isSuccessful && html.isNotBlank()) {
                        var hdUrl: String? = null
                        var sdUrl: String? = null

                        val hdPatterns = listOf(
                            Pattern.compile("[\"']hd_src[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                            Pattern.compile("[\"']hd_src_no_ratelimit[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                            Pattern.compile("[\"']browser_native_hd_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                            Pattern.compile("[\"']playable_url_quality_hd[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                        )

                        for (hp in hdPatterns) {
                            val m = hp.matcher(html)
                            if (m.find()) {
                                val candidate = unescapeFacebookUrl(m.group(1))
                                if (isRealVideoUrl(candidate)) {
                                    hdUrl = candidate
                                    break
                                }
                            }
                        }

                        val sdPatterns = listOf(
                            Pattern.compile("[\"']sd_src[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                            Pattern.compile("[\"']sd_src_no_ratelimit[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                            Pattern.compile("[\"']browser_native_sd_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                            Pattern.compile("[\"']playable_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                        )

                        for (sp in sdPatterns) {
                            val m = sp.matcher(html)
                            if (m.find()) {
                                val candidate = unescapeFacebookUrl(m.group(1))
                                if (isRealVideoUrl(candidate)) {
                                    sdUrl = candidate
                                    break
                                }
                            }
                        }

                        if (hdUrl.isNullOrBlank() && sdUrl.isNullOrBlank()) {
                            val ogPattern = Pattern.compile("<meta\\s+property=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                            if (ogPattern.find()) {
                                val candidate = unescapeFacebookUrl(ogPattern.group(1))
                                if (isRealVideoUrl(candidate)) {
                                    sdUrl = candidate
                                }
                            }
                        }

                        val bestMediaUrl = hdUrl ?: sdUrl
                        if (!bestMediaUrl.isNullOrBlank() && validateFacebookVideoUrl(bestMediaUrl, CRAWLER_USER_AGENT)) {
                            val options = mutableListOf<DownloadOption>()
                            if (!hdUrl.isNullOrBlank()) options.add(DownloadOption("Full HD (1080p)", hdUrl, isAudio = false, quality = "1080p", extension = "mp4"))
                            if (!sdUrl.isNullOrBlank()) options.add(DownloadOption("SD (480p)", sdUrl, isAudio = false, quality = "480p", extension = "mp4"))
                            options.add(DownloadOption("Audio MP3", bestMediaUrl, isAudio = true, quality = "MP3", extension = "mp3"))

                            Log.i(VideoExtractorRepository.TAG, "[FacebookExtractor] Embed Plugin ÉXITO -> $bestMediaUrl")
                            return@withContext Result.success(
                                VideoInfo(
                                    id = videoId,
                                    title = "Facebook Video",
                                    author = "Facebook User",
                                    thumbnailUrl = "",
                                    downloadUrl = bestMediaUrl,
                                    platform = Platform.FACEBOOK,
                                    quality = if (!hdUrl.isNullOrBlank()) "1080p Full HD" else "SD",
                                    isWatermarkFree = true,
                                    originalUrl = url,
                                    options = options
                                )
                            )
                        } else {
                            val idx = html.indexOf("video", ignoreCase = true)
                            val snippet = if (idx >= 0) {
                                val start = maxOf(0, idx - 100)
                                val end = minOf(html.length, idx + 200)
                                html.substring(start, end).replace("\n", " ").replace("\r", " ")
                            } else {
                                html.take(300)
                            }
                            Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Embed Plugin no encontró coincidencias de video válidas. Fragmento alrededor de 'video': ... $snippet ...")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(VideoExtractorRepository.TAG, "[FacebookExtractor] Excepción Embed Plugin Scraper: ${e.message}")
            }
        }

        return@withContext Result.failure(
            lastError ?: Exception("No se pudo extraer el video de Facebook. Verifica que el enlace sea de un Reel o video público.")
        )
    }
}
