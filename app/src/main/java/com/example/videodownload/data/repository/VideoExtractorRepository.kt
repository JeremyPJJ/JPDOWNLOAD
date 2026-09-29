package com.example.videodownload.data.repository

import android.content.Context
import android.util.Log
import com.example.videodownload.data.model.DownloadOption
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.example.videodownload.utils.InstagramCookieManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import org.schabi.newpipe.extractor.downloader.Response
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
// EXTRACTOR MODULAR DE YOUTUBE (NewPipeExtractor v0.26.5 con Muxer de Audio)
// ============================================================================
class NewPipeOkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    private val cookieMap = ConcurrentHashMap<String, String>()

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
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

            return Response(responseCode, responseMessage, responseHeaders, responseBody, url)
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
