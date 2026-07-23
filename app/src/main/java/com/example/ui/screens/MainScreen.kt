package com.example.ui.screens

import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.Warning
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import com.example.BuildConfig
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.data.local.ClienteEntity
import com.example.data.local.MidiaEntity
import com.example.data.local.PlaylistMidiaEntity
import com.example.data.local.TvEntity
import com.example.data.remote.SupabaseManager
import com.example.data.repository.AppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random
import com.example.data.repository.DeviceInfo
import com.example.ui.components.HiddenMaintenancePanel
import java.time.Instant

import com.example.managers.DeviceUnlinkManager

// --- View Models ---

sealed interface UiState {
    object Checking : UiState
    data class Unpaired(val deviceToken: String, val isPairing: Boolean, val error: String? = null) : UiState
    data class Paired(
        val tvId: String,
        val tv: TvEntity?,
        val cliente: ClienteEntity?,
        val slides: List<Pair<PlaylistMidiaEntity, MidiaEntity>>,
        val isSyncing: Boolean
    ) : UiState
}

class PlayerViewModel(val repository: AppRepository) : ViewModel() {
    private val unlinkManager = DeviceUnlinkManager(repository)
    
    private val _uiState = MutableStateFlow<UiState>(UiState.Checking)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _deviceInfo = MutableStateFlow(
        DeviceInfo(
            tvName = "", token = "", clienteName = "", playlistName = "", playlistId = "", 
            status = "Offline", lastSync = null, cacheSize = "0.0 MB", downloadedMediaCount = 0, appVersion = ""
        )
    )
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    private var activeSyncJob: Job? = null
    private var realtimeJob: Job? = null
    private var loopTimerJob: Job? = null
    private var slideChangeJob: Job? = null
    private var pairingPollJob: Job? = null

    // Network status
    var isOnline by mutableStateOf(true)
        private set

    // Slideshow local controls
    var currentSlideIndex by mutableIntStateOf(0)
    var currentSlideTimeElapsed by mutableIntStateOf(0)

    init {
        checkPairingStatus()
    }

    private fun checkPairingStatus() {
        viewModelScope.launch {
            _uiState.value = UiState.Checking
            val tvId = repository.tvIdFlow.firstOrNull()
            var token = repository.deviceTokenFlow.firstOrNull()

            if (tvId != null && token != null) {
                // Device already paired!
                loadPairedState(tvId)
            } else {
                // If token does not exist, generate one VC-1234-AB
                if (token == null) {
                    token = generateDeviceToken()
                    repository.saveDeviceToken(token)
                }
                _uiState.value = UiState.Unpaired(deviceToken = token, isPairing = false)
                startUnpairedPolling(token)
            }
        }
    }

    private fun generateDeviceToken(): String {
        val digits = (1000..9999).random()
        val letters = (1..2).map { ('A'..'Z').random() }.joinToString("")
        return "VC-$digits-$letters"
    }

    // High performance poll/realtime listener before pairing completes
    private fun startUnpairedPolling(token: String) {
        if (pairingPollJob?.isActive == true) {
            Log.d("PlayerViewModel", "Cancelando polling anterior...")
            pairingPollJob?.cancel()
        }
        Log.d("PlayerViewModel", "Iniciando novo polling com o token: $token")
        
        pairingPollJob = viewModelScope.launch {
            while (_uiState.value is UiState.Unpaired) {
                Log.d("PlayerViewModel", "Polling pairing status on Supabase for token: $token")
                val success = repository.tryPairing(token)
                if (success) {
                    val tvId = repository.tvIdFlow.firstOrNull()
                    if (tvId != null) {
                        loadPairedState(tvId)
                        break
                    }
                }
                delay(8000) // Poll every 8 seconds
            }
        }
    }

    private fun loadPairedState(tvId: String) {
        Log.d("PlayerViewModel", "[ENTRY] loadPairedState - Transitioning to Paired screen for TV ID: $tvId")
        // Cancel previous sync or realtime observations
        activeSyncJob?.cancel()
        realtimeJob?.cancel()

        _uiState.value = UiState.Paired(
            tvId = tvId,
            tv = null,
            cliente = null,
            slides = emptyList(),
            isSyncing = true
        )

        // Start periodic sync and realtime listener
        Log.d("PlayerViewModel", "Starting realtime updates and full sync...")
        startRealtimeUpdates(tvId)
        triggerFullSync(tvId)
        startHeartbeatLoop(tvId)
        Log.d("PlayerViewModel", "[EXIT] loadPairedState - Complete")
    }

    fun triggerFullSync(tvId: String) {
        Log.d("PlayerViewModel", "[ENTRY] triggerFullSync - TV ID: $tvId")
        activeSyncJob?.cancel()
        activeSyncJob = viewModelScope.launch {
            val currentState = _uiState.value
            val previousSlides = (currentState as? UiState.Paired)?.slides ?: emptyList()

            if (currentState is UiState.Paired) {
                _uiState.value = currentState.copy(isSyncing = true)
            }

            // Sync from Supabase to Room
            Log.d("PlayerViewModel", "Calling repository.syncData...")
            val success = repository.syncData(tvId)
            Log.d("PlayerViewModel", "Sync data result: $success")

            // Read the cached state to present on UI
            val tv = repository.cacheDao.getTv(tvId)
            val cliente = tv?.cliente_id?.let { repository.cacheDao.getCliente(it) }
            val slides = repository.getPlaylistMediasFromCache(tvId)
            Log.d("PlayerViewModel", "Read from cache - TV: ${tv?.id}, Cliente: ${cliente?.id}, Slides count: ${slides.size}")

            val slidesChanged = slides.size != previousSlides.size || slides.zip(previousSlides).any { (new, old) ->
                new.first.id != old.first.id || new.second.id != old.second.id || new.first.ordem != old.first.ordem
            }

            _deviceInfo.value = repository.getDeviceInfo()

            _uiState.value = UiState.Paired(
                tvId = tvId,
                tv = tv,
                cliente = cliente,
                slides = slides,
                isSyncing = false
            )
            Log.d("PlayerViewModel", "Updated UI state to Paired with ${slides.size} slides.")

            // Start or adjust the slideshow loop only if playlist changed or not running
            if (slidesChanged || slideChangeJob == null || slideChangeJob?.isActive == false) {
                Log.d("PlayerViewModel", "Slides list changed or slideshow was not active. Starting slideshow loop.")
                startSlideshowLoop()
            } else {
                Log.d("PlayerViewModel", "Slides list did not change. Continuing slideshow loop at current index $currentSlideIndex.")
            }
            Log.d("PlayerViewModel", "[EXIT] triggerFullSync")
        }
    }

    private fun startRealtimeUpdates(tvId: String) {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            SupabaseManager.observeRealtimeChanges(tvId).collectLatest { trigger ->
                Log.d("PlayerViewModel", "Realtime update trigger: $trigger")
                if (trigger.startsWith("tv_updated:")) {
                    val recordJson = trigger.substringAfter("tv_updated:")
                    val hasChanges = repository.hasTvContentChanges(tvId, recordJson)
                    if (hasChanges) {
                        Log.d("PlayerViewModel", "Content changes detected in TV remote update, syncing...")
                        triggerFullSync(tvId)
                    } else {
                        Log.d("PlayerViewModel", "Heartbeat-only update received. Ignoring sync to prevent loop.")
                    }
                } else if (trigger == "playlist_updated") {
                    Log.d("PlayerViewModel", "Playlist updated trigger, syncing...")
                    triggerFullSync(tvId)
                } else {
                    triggerFullSync(tvId)
                }
            }
        }
    }

    // Active in-memory backup loop (runs heartbeats and fallback syncs every 10 seconds)
    private fun startHeartbeatLoop(tvId: String) {
        loopTimerJob?.cancel()
        loopTimerJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(10 * 1000) // 10 seconds
                Log.d("PlayerViewModel", "Heartbeat tick. Updating online status.")
                val online = repository.updateHeartbeat(tvId)
                isOnline = online
                _deviceInfo.value = repository.getDeviceInfo()
                
                if (online && repository.checkConfigurationChanges(tvId)) {
                    Log.d("PlayerViewModel", "Configuration changes detected during heartbeat. Triggering full sync.")
                    triggerFullSync(tvId)
                }
            }
        }
    }

    private fun startSlideshowLoop() {
        slideChangeJob?.cancel()
        slideChangeJob = viewModelScope.launch {
            currentSlideIndex = 0
            currentSlideTimeElapsed = 0

            while (true) {
                val state = _uiState.value
                if (state is UiState.Paired && state.slides.isNotEmpty()) {
                    val currentItem = state.slides.getOrNull(currentSlideIndex)
                    if (currentItem != null) {
                        val duration = currentItem.first.duracao ?: currentItem.second.duracao
                        
                        // Increment time elapsed every second
                        for (sec in 0 until duration) {
                            delay(1000)
                            currentSlideTimeElapsed = sec + 1
                        }

                        // Advance to next slide
                        if (state.slides.size > 1) {
                            currentSlideIndex = (currentSlideIndex + 1) % state.slides.size
                            currentSlideTimeElapsed = 0
                            Log.d("PlayerViewModel", "Slideshow advanced to slide index: $currentSlideIndex")
                        } else {
                            currentSlideTimeElapsed = 0
                        }
                    } else {
                        delay(2000)
                    }
                } else {
                    delay(2000)
                }
            }
        }
    }

    fun submitManualToken(token: String) {
        viewModelScope.launch {
            val normalized = repository.normalizeToken(token)
            if (normalized.isEmpty()) return@launch

            // Salva imediatamente o novo token
            repository.saveDeviceToken(normalized)

            val state = _uiState.value
            if (state is UiState.Unpaired) {
                _uiState.value = state.copy(deviceToken = normalized, isPairing = true, error = null)
                
                // Reinicia o polling com o novo token
                startUnpairedPolling(normalized)

                val success = repository.tryPairing(normalized)
                if (success) {
                    val tvId = repository.tvIdFlow.firstOrNull()
                    if (tvId != null) {
                        loadPairedState(tvId)
                    }
                } else {
                    _uiState.value = state.copy(
                        deviceToken = normalized,
                        isPairing = false,
                        error = "Token inválido ou não registrado no painel."
                    )
                }
            }
        }
    }

    fun disconnectDevice() {
        viewModelScope.launch {
            val tvId = (_uiState.value as? UiState.Paired)?.tv?.id
            
            activeSyncJob?.cancel()
            realtimeJob?.cancel()
            loopTimerJob?.cancel()
            slideChangeJob?.cancel()

            unlinkManager.unlinkDevice(tvId) {
                checkPairingStatus()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val tvId = (_uiState.value as? UiState.Paired)?.tv?.id
        if (tvId != null) {
            runBlocking {
                try {
                    withTimeout(2000) {
                        repository.updateHeartbeat(tvId, "Offline")
                    }
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "Failed to set offline status on exit", e)
                }
            }
        }
        activeSyncJob?.cancel()
        realtimeJob?.cancel()
        loopTimerJob?.cancel()
        slideChangeJob?.cancel()
        pairingPollJob?.cancel()
    }
}

// --- Main Composable ---

@Composable
fun MainScreen(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Crossfade(targetState = uiState, label = "ScreenTransition") { state ->
        when (state) {
            is UiState.Checking -> {
                LoadingPlaceholderScreen()
            }
            is UiState.Unpaired -> {
                PairingScreen(
                    deviceToken = state.deviceToken,
                    isPairing = state.isPairing,
                    errorMsg = state.error,
                    onSubmitToken = { viewModel.submitManualToken(it) }
                )
            }
            is UiState.Paired -> {
                PlayerScreen(
                    tvId = state.tvId,
                    tv = state.tv,
                    cliente = state.cliente,
                    slides = state.slides,
                    currentSlideIndex = viewModel.currentSlideIndex,
                    isSyncing = state.isSyncing,
                    viewModel = viewModel,
                    onDisconnect = { viewModel.disconnectDevice() },
                    onRefresh = { viewModel.triggerFullSync(state.tvId) }
                )
            }
        }
    }
}

// --- Placeholder/Loading Screen ---

@Composable
fun LoadingPlaceholderScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F)), // Sleek background
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color(0xFFD0BCFF), // Sleek lavender accent
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Iniciando Vision Central Player...",
                color = Color(0xFFE6E1E5),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// --- Pulsing Green Dot ---
@Composable
fun PulsingGreenDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulsing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color(0xFF4ADE80).copy(alpha = alpha))
    )
}

@Composable
fun rememberCacheSize(): String {
    val context = LocalContext.current
    var sizeStr by remember { mutableStateOf("0.0 MB") }
    LaunchedEffect(Unit) {
        try {
            val mediaDir = context.getExternalFilesDir("media")
            if (mediaDir != null && mediaDir.exists() && mediaDir.isDirectory) {
                val totalBytes = mediaDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
                sizeStr = when {
                    totalBytes == 0L -> "0.0 MB"
                    totalBytes < 1024 -> "$totalBytes B"
                    totalBytes < 1024 * 1024 -> String.format("%.1f KB", totalBytes.toDouble() / 1024)
                    totalBytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", totalBytes.toDouble() / (1024 * 1024))
                    else -> String.format("%.1f GB", totalBytes.toDouble() / (1024 * 1024 * 1024))
                }
            }
        } catch (e: Exception) {
            sizeStr = "0.0 MB"
        }
    }
    return sizeStr
}

@Composable
fun rememberFreeSpace(): String {
    val context = LocalContext.current
    var spaceStr by remember { mutableStateOf("4.0 GB") }
    LaunchedEffect(Unit) {
        try {
            val mediaDir = context.getExternalFilesDir("media")
            if (mediaDir != null && mediaDir.exists()) {
                val usable = mediaDir.usableSpace
                spaceStr = when {
                    usable < 1024 * 1024 * 1024 -> String.format("%.1f MB", usable.toDouble() / (1024 * 1024))
                    else -> String.format("%.1f GB", usable.toDouble() / (1024 * 1024 * 1024))
                }
            }
        } catch (e: Exception) {
            spaceStr = "4.0 GB"
        }
    }
    return spaceStr
}

// --- Pairing Screen (Token) ---

@Composable
fun PairingScreen(
    deviceToken: String,
    isPairing: Boolean,
    errorMsg: String?,
    onSubmitToken: (String) -> Unit
) {
    var tokenInput by remember(deviceToken) { mutableStateOf(deviceToken) }
    val cacheSize = rememberCacheSize()
    val freeSpace = rememberFreeSpace()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F)) // Deep charcoal background
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Icon Box matching the mockup
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFD0BCFF), shape = RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = "Cast Logo",
                        tint = Color(0xFF381E72),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "Vision Central",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "DIGITAL SIGNAGE PLAYER",
                        color = Color(0xFFCAC4D0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
            }
            // Pulsing green dot matching mockup
            PulsingGreenDot()
        }

        // Main content column
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Screen Pairing Title & Subtitle
            Text(
                text = "Pareamento de Dispositivo",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Insira este código no painel administrativo para vincular este player à sua conta.",
                color = Color(0xFF938F99),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Unified Token Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49454F)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
                    .testTag("pairing_instructions_card")
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Token de Ativação",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it.uppercase() },
                        label = { Text("Token de Pareamento") },
                        placeholder = { Text("ex: VC-1234-AB") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFD0BCFF),
                            unfocusedTextColor = Color(0xFFD0BCFF),
                            focusedBorderColor = Color(0xFFD0BCFF),
                            unfocusedBorderColor = Color(0xFF49454F),
                            focusedLabelColor = Color(0xFFD0BCFF),
                            unfocusedLabelColor = Color(0xFFCAC4D0),
                            focusedContainerColor = Color(0xFF1C1B1F),
                            unfocusedContainerColor = Color(0xFF1C1B1F)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_token_input"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSubmitToken(tokenInput) }),
                        shape = RoundedCornerShape(100.dp) // sleek pill shape
                    )

                    Button(
                        onClick = { onSubmitToken(tokenInput) },
                        enabled = !isPairing && tokenInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEADDFF),
                            contentColor = Color(0xFF21005D),
                            disabledContainerColor = Color(0xFF49454F),
                            disabledContentColor = Color(0xFFCAC4D0)
                        ),
                        shape = RoundedCornerShape(100.dp), // pill-shaped button
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("submit_token_button")
                    ) {
                        if (isPairing) {
                            CircularProgressIndicator(color = Color(0xFF21005D), modifier = Modifier.size(24.dp))
                        } else {
                            Text("Conectar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Progress indicators and status info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFD0BCFF),
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Aguardando confirmação do painel...",
                            color = Color(0xFFCAC4D0),
                            fontSize = 13.sp
                        )
                    }

                    if (errorMsg != null) {
                        Text(
                            text = errorMsg,
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Status Metrics Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Metric 1: Supabase Sync
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1C1B1F), shape = RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF49454F), shape = RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "SUPABASE SYNC",
                            color = Color(0xFFCAC4D0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFF4ADE80), shape = CircleShape)
                            )
                            Text(
                                text = "Online",
                                color = Color(0xFF4ADE80),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Metric 2: Cache Local
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1C1B1F), shape = RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF49454F), shape = RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "CACHE LOCAL",
                            color = Color(0xFFCAC4D0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "$cacheSize / $freeSpace",
                            color = Color(0xFFE6E1E5),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Bottom Navigation / Info Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = Color(0xFF49454F))
                .background(Color(0xFF1C1B1F))
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Dispositivo: ${android.os.Build.MODEL}",
                    color = Color(0xFFCAC4D0),
                    fontSize = 12.sp
                )
                Text(
                    text = "v2.0.4-clean-stable",
                    color = Color(0xFF938F99),
                    fontSize = 10.sp
                )
            }
            // Settings Icon Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF49454F), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Device Information",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// --- Player Screen (Loop Slideshow & Overlays) ---

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerScreen(
    tvId: String,
    tv: TvEntity?,
    cliente: ClienteEntity?,
    slides: List<Pair<PlaylistMidiaEntity, MidiaEntity>>,
    currentSlideIndex: Int,
    isSyncing: Boolean,
    viewModel: PlayerViewModel,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit
) {
    // Apply Rotation based on tv.rotacao (0, 90, 180, 270)
    val rotationAngle = tv?.rotacao ?: 0
    val isOnline = viewModel.isOnline
    val context = LocalContext.current

    // Single ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // Single WebView instance
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 10; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Safari/537.36"
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.e("WebView", "WebView loading error: $description")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
            webView.destroy()
        }
    }

    val currentMidia = slides.getOrNull(currentSlideIndex)?.second

    // Handle Media changes and push to ExoPlayer/WebView
    LaunchedEffect(currentMidia, isOnline) {
        if (currentMidia == null) return@LaunchedEffect

        when (currentMidia.tipo) {
            "video" -> {
                webView.loadUrl("about:blank")
                webView.onPause()

                val videoUri = if (isOnline) {
                    val url = currentMidia.url_externa ?: "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/midias/${currentMidia.url_storage}"
                    Uri.parse(url)
                } else {
                    if (!currentMidia.local_file_path.isNullOrEmpty()) {
                        Uri.fromFile(File(currentMidia.local_file_path!!))
                    } else {
                        val url = currentMidia.url_externa ?: "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/midias/${currentMidia.url_storage}"
                        Uri.parse(url)
                    }
                }

                val mediaItem = MediaItem.fromUri(videoUri)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.setVolume((tv?.volume ?: 100).toFloat() / 100f)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
            "image" -> {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                webView.loadUrl("about:blank")
                webView.onPause()
            }
            else -> { // web
                exoPlayer.stop()
                exoPlayer.clearMediaItems()

                if (isOnline) {
                    webView.onResume()
                    val rawUrl = currentMidia.url_externa ?: "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/midias/${currentMidia.url_storage}" ?: "about:blank"
                    val resolvedUrl = resolveEmbedUrl(rawUrl, currentMidia.tipo)
                    webView.loadUrl(resolvedUrl)
                } else {
                    webView.loadUrl("about:blank")
                    webView.onPause()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Rotational wrapper to support vertical screen rotation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(rotationAngle.toFloat())
        ) {
            if (slides.isEmpty()) {
                // Beautiful placeholder empty state
                EmptyPlaylistPlaceholder(isSyncing, onRefresh)
            } else {
                // Base layer: WebView (only visible if web)
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize().alpha(if (currentMidia?.tipo != "image" && currentMidia?.tipo != "video" && isOnline) 1f else 0f)
                )

                if (currentMidia?.tipo != "image" && currentMidia?.tipo != "video" && !isOnline) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SignalCellularConnectedNoInternet0Bar,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Conteúdo indisponível offline",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Middle layer: ExoPlayer (only visible if video)
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize().alpha(if (currentMidia?.tipo == "video") 1f else 0f)
                )

                // Top layer: Image (with Crossfade for smooth image-to-image transitions)
                if (currentMidia?.tipo == "image") {
                    Crossfade(targetState = currentMidia, label = "ImageFade") { targetMidia ->
                        ImageSlide(targetMidia, isOnline)
                    }
                }
            }

            // Text Overlays and Scrolling Ticker
            OverlaysContainer(tv = tv, cliente = cliente)
        }

        // Invisible management settings drawer trigger (Bottom-right hidden corner to disconnect/troubleshoot)
        val deviceInfo by viewModel.deviceInfo.collectAsState()
        HiddenMaintenancePanel(
            deviceInfo = deviceInfo,
            onDisconnect = onDisconnect,
            onRefresh = onRefresh,
            modifier = Modifier
                .size(64.dp)
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp)
                .testTag("hidden_admin_corner")
        )
    }
}

// --- Individual Slide Renderers ---

@Composable
fun ImageSlide(media: MidiaEntity, isOnline: Boolean) {
    // Prefer absolute local file path if offline, otherwise prefer URL
    val imageSource = if (isOnline) {
        media.url_externa ?: "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/midias/${media.url_storage}"
    } else {
        if (!media.local_file_path.isNullOrEmpty()) {
            File(media.local_file_path!!)
        } else {
            media.url_externa ?: "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/midias/${media.url_storage}"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageSource,
            contentDescription = media.nome,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Transforms standard links to optimized full-bleed TV embed links.
 */
private fun resolveEmbedUrl(url: String, type: String): String {
    return try {
        if (type == "youtube" || url.contains("youtube.com") || url.contains("youtu.be")) {
            val videoId = if (url.contains("youtu.be/")) {
                url.substringAfterLast("youtu.be/").substringBefore("?")
            } else if (url.contains("v=")) {
                url.substringAfter("v=").substringBefore("&")
            } else if (url.contains("embed/")) {
                url.substringAfter("embed/").substringBefore("?")
            } else {
                ""
            }
            if (videoId.isNotEmpty()) {
                "https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&loop=1&playlist=$videoId"
            } else {
                url
            }
        } else {
            url
        }
    } catch (e: Exception) {
        url
    }
}

// --- Overlays (Top / Bottom Text Overlays & Marquee Ticker) ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlaysContainer(tv: TvEntity?, cliente: ClienteEntity?) {
    Box(modifier = Modifier.fillMaxSize()) {
        
        // 1. Top Text Overlay
        if (tv?.texto_superior_visivel == true && !tv.texto_superior.isNullOrBlank()) {
            val textColor = parseColor(tv.texto_superior_cor, Color.White)
            val fontSize = parseFontSize(tv.texto_superior_tamanho, 24)
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .align(Alignment.TopCenter)
            ) {
                Text(
                    text = tv.texto_superior!!,
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 2. Bottom Text Overlay & Marquee Ticker (Client Ticker)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            // Static Bottom Overlay
            if (tv?.texto_inferior_visivel == true && !tv.texto_inferior.isNullOrBlank()) {
                val textColor = parseColor(tv.texto_inferior_cor, Color.White)
                val fontSize = parseFontSize(tv.texto_inferior_tamanho, 20)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = tv.texto_inferior!!,
                        color = textColor,
                        fontSize = fontSize.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Marquee Client Ticker bar
            if (!cliente?.ticker_text.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000)) // solid semi-trans black background
                        .border(width = (0.5).dp, color = Color(0xFF334155))
                        .padding(vertical = 10.dp, horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF3B82F6), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "AVISO",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = cliente!!.ticker_text!!,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                }
            }
        }
    }
}

private fun parseColor(hex: String?, fallback: Color): Color {
    if (hex == null) return fallback
    return try {
        val clean = hex.removePrefix("#")
        if (clean.length == 6) {
            Color(android.graphics.Color.parseColor("#$clean"))
        } else if (clean.length == 8) {
            Color(android.graphics.Color.parseColor("#$clean"))
        } else {
            fallback
        }
    } catch (e: Exception) {
        fallback
    }
}

private fun parseFontSize(sizeStr: String?, fallback: Int): Int {
    if (sizeStr == null) return fallback
    return try {
        sizeStr.filter { it.isDigit() }.toInt()
    } catch (e: Exception) {
        fallback
    }
}

// --- Empty State ---

@Composable
fun EmptyPlaylistPlaceholder(isSyncing: Boolean, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F)), // Sleek charcoal background
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = "Empty TV",
                tint = Color(0xFFCAC4D0), // Sleek secondary text tint
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isSyncing) "Sincronizando conteúdo..." else "Sem Conteúdo Ativo",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (!isSyncing) {
                Text(
                    text = "Esta tela está vinculada, mas não há nenhuma mídia na playlist selecionada.",
                    color = Color(0xFF938F99), // Sleek tertiary color
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEADDFF),
                    contentColor = Color(0xFF21005D),
                    disabledContainerColor = Color(0xFF49454F),
                    disabledContentColor = Color(0xFFCAC4D0)
                ),
                shape = RoundedCornerShape(100.dp) // Sleek pill-shaped button
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(color = Color(0xFF21005D), modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sincronizar Agora", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// --- Invisible Admin Management Dialog ---

@Composable
fun AdminDialog(
    deviceInfo: DeviceInfo,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDisconnectConfirmation by remember { mutableStateOf(false) }

    fun formatRelativeTime(instant: Instant?): String {
        if (instant == null) return ""
        val now = Instant.now()
        val seconds = java.time.Duration.between(instant, now).seconds
        return when {
            seconds < 10 -> "Agora"
            seconds < 60 -> "há ${seconds} segundos"
            seconds < 3600 -> {
                val m = seconds / 60
                if (m == 1L) "há 1 minuto" else "há $m minutos"
            }
            seconds < 86400 -> {
                val h = seconds / 3600
                if (h == 1L) "há 1 hora" else "há $h horas"
            }
            else -> {
                val d = seconds / 86400
                if (d == 1L) "há 1 dia" else "há $d dias"
            }
        }
    }

    if (showDisconnectConfirmation) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDisconnectConfirmation = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .width(400.dp)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Deseja realmente desvincular esta TV?",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Todos os arquivos em cache serão removidos e será necessário informar um novo token para ativar novamente.",
                        color = Color(0xFF94A3B8),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showDisconnectConfirmation = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancelar", color = Color.White)
                        }
                        Button(
                            onClick = {
                                showDisconnectConfirmation = false
                                onDisconnect()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Desvincular", color = Color.White)
                        }
                    }
                }
            }
        }
        return
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(400.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.DesktopMac,
                    contentDescription = "Admin",
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Vision Central Player",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // Details Grid
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminDetailRow("Token vinculado:", deviceInfo.token)
                    AdminDetailRow("Nome da TV:", deviceInfo.tvName)
                    AdminDetailRow("Cliente:", deviceInfo.clienteName)
                    AdminDetailRow("Playlist:", deviceInfo.playlistName)
                    AdminDetailRow("Status:", deviceInfo.status)
                    AdminDetailRow("Última sincronização:", formatRelativeTime(deviceInfo.lastSync))
                    AdminDetailRow("Versão do aplicativo:", deviceInfo.appVersion)
                    AdminDetailRow("Uso de armazenamento local:", deviceInfo.cacheSize)
                    AdminDetailRow("Quantidade de mídias em cache:", "${deviceInfo.downloadedMediaCount}")
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sincronizar Agora", color = Color.White)
                    }
                    
                    Button(
                        onClick = { /* Not implemented yet, just visual */ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Verificar Atualizações", color = Color.White)
                    }
                    
                    Button(
                        onClick = { showDisconnectConfirmation = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Desvincular Dispositivo", color = Color.White)
                    }
                    
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Fechar", color = Color(0xFF94A3B8))
                    }
                }
            }
        }
    }
}

@Composable
fun AdminDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(text = value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
