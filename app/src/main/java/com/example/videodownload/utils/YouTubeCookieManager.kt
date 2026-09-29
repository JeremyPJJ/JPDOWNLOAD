package com.example.videodownload.utils

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File
import java.io.FileWriter

object YouTubeCookieManager {

    private const val TAG = "YouTubeCookieManager"
    private const val COOKIE_FILE_NAME = "youtube_cookies.txt"

    fun getCookieFile(context: Context): File {
        return File(context.filesDir, COOKIE_FILE_NAME)
    }

    fun hasCookies(context: Context): Boolean {
        val file = getCookieFile(context)
        return file.exists() && file.length() > 50
    }

    fun getCookieHeader(context: Context): String? {
        val file = getCookieFile(context)
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            val cookiesList = mutableListOf<String>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split("\t")
                if (parts.size >= 7) {
                    val key = parts[5].trim()
                    val value = parts[6].trim()
                    cookiesList.add("$key=$value")
                }
            }
            if (cookiesList.isNotEmpty()) cookiesList.joinToString("; ") else null
        } catch (e: Exception) {
            Log.e(TAG, "[getCookieHeader] Error leyendo cookies: ${e.message}")
            null
        }
    }

    fun saveCookiesFromWebView(context: Context): Boolean {
        try {
            val youtubeCookies = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
            val googleCookies = CookieManager.getInstance().getCookie("https://accounts.google.com") ?: ""
            val combined = "$youtubeCookies; $googleCookies".trim(';', ' ')

            if (combined.isBlank() || (!combined.contains("LOGIN_INFO") && !combined.contains("SID") && !combined.contains("HSID"))) {
                Log.w(TAG, "[saveCookiesFromWebView] No se encontraron cookies de sesión válidas de YouTube/Google.")
                return false
            }

            val cookieFile = getCookieFile(context)
            FileWriter(cookieFile, false).use { writer ->
                writer.write("# Netscape HTTP Cookie File\n")
                writer.write("# https://curl.haxx.se/rfc/cookie_spec.html\n")
                writer.write("# This is a generated file! Do not edit.\n\n")

                val pairs = combined.split(";")
                val seenKeys = mutableSetOf<String>()

                for (pair in pairs) {
                    val trimmed = pair.trim()
                    val eqIndex = trimmed.indexOf("=")
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        val value = trimmed.substring(eqIndex + 1).trim()
                        if (key.isNotBlank() && value.isNotBlank() && !seenKeys.contains(key)) {
                            seenKeys.add(key)
                            writer.write(".youtube.com\tTRUE\t/\tTRUE\t2147483647\t$key\t$value\n")
                            writer.write(".google.com\tTRUE\t/\tTRUE\t2147483647\t$key\t$value\n")
                        }
                    }
                }
            }

            Log.i(TAG, "[saveCookiesFromWebView] Cookies de YouTube y Google guardadas en formato Netscape: ${cookieFile.absolutePath} (${cookieFile.length()} bytes)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "[saveCookiesFromWebView] Error guardando cookies de YouTube: ${e.message}", e)
            return false
        }
    }

    fun clearCookies(context: Context) {
        try {
            val cookieFile = getCookieFile(context)
            if (cookieFile.exists()) {
                cookieFile.delete()
            }
            CookieManager.getInstance().removeAllCookies(null)
            Log.i(TAG, "[clearCookies] Cookies de YouTube eliminadas del dispositivo.")
        } catch (e: Exception) {
            Log.e(TAG, "[clearCookies] Error eliminando cookies: ${e.message}")
        }
    }
}
