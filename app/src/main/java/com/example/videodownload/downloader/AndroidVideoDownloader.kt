package com.example.videodownload.downloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.videodownload.data.model.VideoInfo
import java.io.File

class AndroidVideoDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun downloadVideo(videoInfo: VideoInfo): Long {
        val cleanTitle = videoInfo.title.replace("[^a-zA-Z0-9_ -]".toRegex(), "_")
            .take(40)
            .ifEmpty { "video_${System.currentTimeMillis()}" }

        val fileName = "${videoInfo.platform.name.lowercase()}_${cleanTitle}_${System.currentTimeMillis()}.mp4"

        val request = DownloadManager.Request(Uri.parse(videoInfo.downloadUrl))
            .setTitle(videoInfo.title)
            .setDescription("Descargando de ${videoInfo.platform.displayName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMofor(true)
            .setAllowedOverMetered(true)

        // Guardar en la carpeta pública Movies/VideoDownload para que aparezca en la Galería
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_MOVIES,
            "VideoDownload/$fileName"
        )

        // Iniciar la descarga del sistema
        return downloadManager.enqueue(request)
    }

    private fun DownloadManager.Request.setAllowedOverMofor(allowed: Boolean): DownloadManager.Request {
        return this.setAllowedNetworkTypes(
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
        )
    }

    fun getDownloadStatus(downloadId: Long): Pair<Int, Int> {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor != null && cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

            val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
            val downloaded = if (bytesDownloadedIndex >= 0) cursor.getInt(bytesDownloadedIndex) else 0
            val total = if (bytesTotalIndex >= 0) cursor.getInt(bytesTotalIndex) else 0

            cursor.close()
            return Pair(status, if (total > 0) ((downloaded * 100L) / total).toInt() else 0)
        }
        cursor?.close()
        return Pair(-1, 0)
    }
}
