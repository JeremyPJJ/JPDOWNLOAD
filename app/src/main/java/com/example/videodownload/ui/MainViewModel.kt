package com.example.videodownload.ui

import android.app.Application
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videodownload.data.model.DownloadOption
import com.example.videodownload.data.model.DownloadUiState
import com.example.videodownload.data.model.DownloadedItem
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.example.videodownload.data.repository.VideoExtractorRepository
import com.example.videodownload.downloader.AndroidVideoDownloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoExtractorRepository(application)
    private val downloader = AndroidVideoDownloader(application)

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _uiState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private val _detectedPlatform = MutableStateFlow(Platform.UNKNOWN)
    val detectedPlatform: StateFlow<Platform> = _detectedPlatform.asStateFlow()

    private val _historyList = MutableStateFlow<List<DownloadedItem>>(emptyList())
    val historyList: StateFlow<List<DownloadedItem>> = _historyList.asStateFlow()

    fun onUrlInputChanged(newUrl: String) {
        _urlInput.value = newUrl
        _detectedPlatform.value = repository.detectPlatform(newUrl)
        if (newUrl.isBlank()) {
            _uiState.value = DownloadUiState.Idle
        }
    }

    fun pasteFromClipboard() {
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                onUrlInputChanged(text)
                fetchVideoInfo()
            }
        }
    }

    fun handleSharedUrl(sharedText: String) {
        if (sharedText.isNotBlank()) {
            onUrlInputChanged(sharedText)
            fetchVideoInfo()
        }
    }

    fun fetchVideoInfo() {
        val url = _urlInput.value.trim()
        if (url.isBlank()) {
            _uiState.value = DownloadUiState.Error("Ingresa o pega un enlace de video válido.")
            return
        }

        viewModelScope.launch {
            _uiState.value = DownloadUiState.Loading("Analizando enlace y obteniendo calidades disponibles...")
            val result = repository.extractVideoInfo(url)

            result.onSuccess { info ->
                _uiState.value = DownloadUiState.InfoLoaded(info)
            }.onFailure { exception ->
                _uiState.value = DownloadUiState.Error(
                    exception.localizedMessage ?: "No se pudo procesar el enlace. Revisa que sea público."
                )
            }
        }
    }

    fun downloadMedia(videoInfo: VideoInfo, option: DownloadOption? = null) {
        viewModelScope.launch {
            try {
                val label = option?.label ?: videoInfo.quality
                _uiState.value = DownloadUiState.Downloading(2, "Iniciando descarga...")

                if (option?.audioDownloadUrl != null && !option.isAudio) {
                    downloader.downloadMediaAsync(videoInfo, option) { progressPercent, statusMsg ->
                        _uiState.value = DownloadUiState.Downloading(
                            progress = progressPercent,
                            title = statusMsg
                        )
                    }

                    val path = "Movies/VideoDownload/"
                    _uiState.value = DownloadUiState.Success(
                        videoInfo = videoInfo,
                        filePath = path
                    )

                    val newItem = DownloadedItem(
                        id = videoInfo.id,
                        title = "${videoInfo.title} [${option.label}]",
                        platform = videoInfo.platform,
                        localUri = option.downloadUrl
                    )
                    _historyList.value = listOf(newItem) + _historyList.value
                    return@launch
                }

                val downloadId = downloader.downloadMedia(videoInfo, option)

                var isComplete = false
                while (!isComplete) {
                    delay(500)
                    val (status, progress) = downloader.getDownloadStatus(downloadId)

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isComplete = true
                            val path = if (option?.isAudio == true) "Music/VideoDownload/" else "Movies/VideoDownload/"
                            _uiState.value = DownloadUiState.Success(
                                videoInfo = videoInfo,
                                filePath = path
                            )

                            val newItem = DownloadedItem(
                                id = videoInfo.id,
                                title = "${videoInfo.title} [${option?.label ?: "HD"}]",
                                platform = videoInfo.platform,
                                localUri = option?.downloadUrl ?: videoInfo.downloadUrl
                            )
                            _historyList.value = listOf(newItem) + _historyList.value
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isComplete = true
                            _uiState.value = DownloadUiState.Error("La descarga falló. Intenta de nuevo.")
                        }
                        else -> {
                            val currentProgress = if (progress in 1..99) progress else 15
                            _uiState.value = DownloadUiState.Downloading(
                                progress = currentProgress,
                                title = "Descargando ${videoInfo.title} (${option?.label ?: "HD"})..."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = DownloadUiState.Error("Error iniciando la descarga: ${e.localizedMessage}")
            }
        }
    }

    fun resetState() {
        _uiState.value = DownloadUiState.Idle
        _urlInput.value = ""
        _detectedPlatform.value = Platform.UNKNOWN
    }
}
