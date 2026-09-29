package com.example.videodownload

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class VideoDownloadApp : Application() {

    companion object {
        private const val TAG = "VideoDownloadApp"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Log.i(TAG, "[onCreate] Native Executable Engine (YoutubeDL + FFmpeg) inicializado exitosamente.")
        } catch (e: Exception) {
            Log.e(TAG, "[onCreate] Error inicializando YoutubeDL: ${e.message}", e)
        }
    }
}
