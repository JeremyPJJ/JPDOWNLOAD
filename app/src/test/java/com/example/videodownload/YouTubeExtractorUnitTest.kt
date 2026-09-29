package com.example.videodownload

import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

enum class YouTubeErrorType {
    YOUTUBE_TEMPORARILY_BLOCKED,
    YOUTUBE_LOGIN_REQUIRED,
    YOUTUBE_VIDEO_PRIVATE,
    YOUTUBE_VIDEO_UNAVAILABLE,
    YOUTUBE_NETWORK_ERROR,
    YOUTUBE_EXTRACTOR_ERROR,
    YOUTUBE_UNSUPPORTED
}

class YouTubeExtractorUnitTest {

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

    private fun classifyYouTubeError(errorMsg: String, hasCookies: Boolean): Pair<YouTubeErrorType, String> {
        val lower = errorMsg.lowercase()
        return when {
            lower.contains("sign in to confirm") || lower.contains("login_required") || lower.contains("bot") || lower.contains("precondition check failed") -> {
                val userMsg = if (hasCookies) {
                    "La sesión de Google expiró, vuelve a iniciar sesión."
                } else {
                    "YouTube pide verificación. Inicia sesión con Google (cuenta secundaria) o cambia de red."
                }
                YouTubeErrorType.YOUTUBE_TEMPORARILY_BLOCKED to userMsg
            }
            lower.contains("private video") || lower.contains("video privado") -> {
                YouTubeErrorType.YOUTUBE_VIDEO_PRIVATE to "El video de YouTube es privado."
            }
            lower.contains("video unavailable") || lower.contains("no disponible") || lower.contains("404") -> {
                YouTubeErrorType.YOUTUBE_VIDEO_UNAVAILABLE to "El video de YouTube no está disponible o fue eliminado."
            }
            lower.contains("network") || lower.contains("unable to resolve host") || lower.contains("timeout") -> {
                YouTubeErrorType.YOUTUBE_NETWORK_ERROR to "Error de conexión al conectar con YouTube. Revisa tu red."
            }
            else -> {
                YouTubeErrorType.YOUTUBE_EXTRACTOR_ERROR to "No se pudo procesar el video de YouTube."
            }
        }
    }

    @Test
    fun testYouTubeUrlIdExtraction_WatchUrl() {
        val url = "https://www.youtube.com/watch?v=wvsq1S_jQic"
        val id = extractVideoId(url)
        assertEquals("wvsq1S_jQic", id)
    }

    @Test
    fun testYouTubeUrlIdExtraction_ShortsUrl() {
        val url = "https://www.youtube.com/shorts/wvsq1S_jQic"
        val id = extractVideoId(url)
        assertEquals("wvsq1S_jQic", id)
    }

    @Test
    fun testYouTubeUrlIdExtraction_YoutuBeUrl() {
        val url = "https://youtu.be/wvsq1S_jQic?si=4F1Byi9"
        val id = extractVideoId(url)
        assertEquals("wvsq1S_jQic", id)
    }

    @Test
    fun testYouTubeUrlIdExtraction_RawId() {
        val rawId = "wvsq1S_jQic"
        val id = extractVideoId(rawId)
        assertEquals("wvsq1S_jQic", id)
    }

    @Test
    fun testErrorClassification_BotCheckWithoutCookies() {
        val err = "SignInConfirmNotBotException: YouTube probably temporarily blocked anonymous watch access with this IP, got error LOGIN_REQUIRED: Sign in to confirm that you're not a bot"
        val (type, msg) = classifyYouTubeError(err, hasCookies = false)

        assertEquals(YouTubeErrorType.YOUTUBE_TEMPORARILY_BLOCKED, type)
        assertTrue(msg.contains("Inicia sesión con Google"))
    }

    @Test
    fun testErrorClassification_BotCheckWithCookies() {
        val err = "ERROR: [youtube] fQUYRG64oGE: Sign in to confirm you're not a bot"
        val (type, msg) = classifyYouTubeError(err, hasCookies = true)

        assertEquals(YouTubeErrorType.YOUTUBE_TEMPORARILY_BLOCKED, type)
        assertTrue(msg.contains("La sesión de Google expiró"))
    }

    @Test
    fun testErrorClassification_PrivateVideo() {
        val err = "This is a private video. Please sign in to view."
        val (type, msg) = classifyYouTubeError(err, hasCookies = false)

        assertEquals(YouTubeErrorType.YOUTUBE_VIDEO_PRIVATE, type)
        assertTrue(msg.contains("privado"))
    }
}
