package com.example.videodownload.ui

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videodownload.R
import com.example.videodownload.data.model.DownloadUiState
import com.example.videodownload.data.model.Platform

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel
) {
    val urlInput by viewModel.urlInput.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val detectedPlatform by viewModel.detectedPlatform.collectAsState()
    val historyList by viewModel.historyList.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.mipmap.ic_jpdown_foreground),
                                contentDescription = "Logo JPDOWNLOAD",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Text(
                            text = "JPDOWNLOAD",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                // Selector / Banner de Plataformas Soportadas
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Plataformas Soportadas:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            PlatformChip("TikTok", Color(0xFF000000))
                            PlatformChip("YouTube", Color(0xFFFF0000))
                            PlatformChip("Instagram", Color(0xFFE1306C))
                            PlatformChip("Facebook", Color(0xFF1877F2))
                            PlatformChip("Twitter/X", Color(0xFF1DA1F2))
                        }
                    }
                }
            }

            item {
                // Campo de texto de URL con botón de Pegar
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { viewModel.onUrlInputChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pega el enlace del video aquí...") },
                    placeholder = { Text("https://www.tiktok.com/@user/video/...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onUrlInputChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Limpiar"
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.pasteFromClipboard() }) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Pegar",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                )
            }

            item {
                // Botón de Analizar enlace
                Button(
                    onClick = { viewModel.fetchVideoInfo() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = urlInput.isNotBlank() && uiState !is DownloadUiState.Loading
                ) {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Analizar Video",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            // Indicador de plataforma detectada
            if (detectedPlatform != Platform.UNKNOWN) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = "Plataforma detectada:", fontSize = 12.sp)
                        PlatformBadge(platform = detectedPlatform)
                    }
                }
            }

            // Seccion de Estados (Loading, Loaded, Downloading, Success, Error)
            item {
                AnimatedContent(
                    targetState = uiState,
                    label = "UI State Transition"
                ) { state ->
                    when (state) {
                        is DownloadUiState.Idle -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "Pega un enlace para descargar en HD o MP3 sin marcas de agua",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }

                        is DownloadUiState.Loading -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                    Text(
                                        text = state.message,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        is DownloadUiState.InfoLoaded -> {
                            VideoPreviewCard(
                                videoInfo = state.videoInfo,
                                onDownloadClick = { selectedOption ->
                                    viewModel.downloadMedia(state.videoInfo, selectedOption)
                                }
                            )
                        }

                        is DownloadUiState.Downloading -> {
                            ProgressCard(progress = state.progress, title = state.title)
                        }

                        is DownloadUiState.Success -> {
                            SuccessCard(
                                videoInfo = state.videoInfo,
                                filePath = state.filePath,
                                onReset = { viewModel.resetState() }
                            )
                        }

                        is DownloadUiState.Error -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Error al procesar",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Button(
                                        onClick = { viewModel.fetchVideoInfo() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Reintentar")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Historial de descargas
            if (historyList.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Historial de Descargas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(historyList) { item ->
                    DownloadHistoryItem(item = item)
                }
            }
        }
    }
}

@Composable
fun PlatformChip(name: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
