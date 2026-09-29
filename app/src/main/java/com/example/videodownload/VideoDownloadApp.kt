package com.example.videodownload

import android.app.Application
import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class VideoDownloadApp : Application() {

    companion object {
        private const val TAG = "VideoDownloadApp"
        private const val PREFS_NAME = "yt_dlp_prefs"
        private const val KEY_LAST_UPDATE = "last_update_ms"
        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Log.i(TAG, "[onCreate] Native Executable Engine (YoutubeDL + FFmpeg) inicializado exitosamente.")

            checkAndApplyDailyUpdate(this)
        } catch (e: Exception) {
            Log.e(TAG, "[onCreate] Error inicializando YoutubeDL: ${e.message}", e)
        }
    }

    private fun checkAndApplyDailyUpdate(context: Context) {
        Thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
                val now = System.currentTimeMillis()

                if (now - lastUpdate > ONE_DAY_MS) {
                    Log.i(TAG, "[DailyUpdate] Han pasado más de 24 horas. Intentando actualización de yt-dlp...")
                    val status = YoutubeDL.getInstance().updateYoutubeDL(context)
                    prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
                    Log.i(TAG, "[DailyUpdate] yt-dlp actualizado con éxito a la versión más reciente. Status: $status")
                } else {
                    Log.d(TAG, "[DailyUpdate] yt-dlp actualizado recientemente (hace ${((now - lastUpdate) / (1000 * 60 * 60))} horas). Omite actualización.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[DailyUpdate] No se pudo completar la actualización diaria de yt-dlp: ${e.message}")
            }
        }.start()
    }
}
