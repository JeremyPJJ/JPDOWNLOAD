package com.example.videodownload.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object AudioVideoMuxer {

    private const val TAG = "AudioVideoMuxer"

    fun mux(videoFile: File, audioFile: File, outputFile: File, qualityLabel: String = "HD"): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        Log.d(TAG, "==================================================")
        Log.d(TAG, "[Muxer Start] Calidad Elegida: '$qualityLabel'")
        Log.d(TAG, "[Muxer Start] Archivo Video Temporal: ${videoFile.name} (${videoFile.length() / 1024} KB)")
        Log.d(TAG, "[Muxer Start] Archivo Audio Temporal: ${audioFile.name} (${audioFile.length() / 1024} KB)")

        try {
            // 1. Extraer e inspeccionar pista de video
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            val videoTrackIndex = selectTrack(videoExtractor, "video/")
            if (videoTrackIndex < 0) {
                Log.e(TAG, "[Muxer Error] No se encontró pista de video en el archivo temporal: ${videoFile.name}")
                return false
            }
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "desconocido"
            val videoWidth = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) videoFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
            val videoHeight = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) videoFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0

            Log.i(TAG, "[Muxer Inspector] VIDEO TRACT -> MIME/Códec: $videoMime | Resolución: ${videoWidth}x${videoHeight}")

            // 2. Extraer e inspeccionar pista de audio
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            val audioTrackIndex = selectTrack(audioExtractor, "audio/")
            if (audioTrackIndex < 0) {
                Log.e(TAG, "[Muxer Error] No se encontró pista de audio en el archivo temporal: ${audioFile.name}")
                return false
            }
            audioExtractor.selectTrack(audioTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "desconocido"
            val audioBitrate = if (audioFormat.containsKey(MediaFormat.KEY_BIT_RATE)) audioFormat.getInteger(MediaFormat.KEY_BIT_RATE) else -1
            val audioChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            val audioSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0

            Log.i(TAG, "[Muxer Inspector] AUDIO TRACT -> MIME/Códec: $audioMime | Bitrate: $audioBitrate bps | Canales: $audioChannels | Freq: ${audioSampleRate}Hz")

            if (outputFile.exists()) {
                outputFile.delete()
            }

            // 3. Crear Muxer MPEG-4 y agregar ambas pistas
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            Log.d(TAG, "[Muxer Process] Escribiendo muestras de video...")
            copyTrack(videoExtractor, muxer, muxerVideoTrack, buffer, bufferInfo)

            Log.d(TAG, "[Muxer Process] Escribiendo muestras de audio...")
            copyTrack(audioExtractor, muxer, muxerAudioTrack, buffer, bufferInfo)

            muxer.stop()
            muxer.release()
            muxer = null

            videoExtractor.release()
            videoExtractor = null
            audioExtractor.release()
            audioExtractor = null

            Log.i(TAG, "[Muxer Complete] Muxing finalizado en disco: ${outputFile.name} (${outputFile.length() / 1024} KB)")

            // 4. VERIFICACIÓN POST-FUSIONADO CON MediaExtractor
            val verified = verifyOutputFile(outputFile)
            if (!verified) {
                Log.e(TAG, "[Muxer Error] VERIFICACIÓN FALLIDA -> El archivo $outputFile no tiene 2 pistas (Video + Audio). Eliminándolo.")
                if (outputFile.exists()) outputFile.delete()
                return false
            }

            Log.i(TAG, "==================================================")
            Log.i(TAG, "[Muxer ÉXITO TOTAL] Video $qualityLabel verificado con 2 pistas (Video+Audio) correctamente en ${outputFile.absolutePath}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "[Muxer Exception] Error grave durante la combinación MediaMuxer para '$qualityLabel': ${e.message}", e)
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}

            // Borrar archivo parcial corrupto
            if (outputFile.exists()) {
                outputFile.delete()
                Log.w(TAG, "[Muxer Cleanup] Archivo de salida corrupto eliminado: ${outputFile.name}")
            }
            return false
        }
    }

    private fun verifyOutputFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) {
            Log.e(TAG, "[Verify] Archivo inexistente o demasiado pequeño (${file.length()} bytes)")
            return false
        }

        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            val trackCount = extractor.trackCount
            Log.d(TAG, "[Verify Inspector] Total de pistas detectadas en archivo final: $trackCount")

            var hasVideo = false
            var hasAudio = false

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                Log.d(TAG, "[Verify Inspector] Pista #$i -> MIME: $mime")
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }

            extractor.release()
            return hasVideo && hasAudio
        } catch (e: Exception) {
            Log.e(TAG, "[Verify Exception] Error verificando archivo final: ${e.message}", e)
            try { extractor?.release() } catch (_: Exception) {}
            return false
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) break

            bufferInfo.presentationTimeUs = extractor.sampleTime
            val sampleFlags = extractor.sampleFlags
            bufferInfo.flags = if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
}
