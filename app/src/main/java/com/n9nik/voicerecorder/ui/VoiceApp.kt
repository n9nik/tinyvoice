package com.n9nik.voicerecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.n9nik.voicerecorder.ads.BannerAd
import com.n9nik.voicerecorder.domain.AudioTrimmer
import com.n9nik.voicerecorder.domain.Recording
import com.n9nik.voicerecorder.domain.RecordingRepository
import com.n9nik.voicerecorder.recorder.RecordingService
import com.n9nik.voicerecorder.recorder.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceApp(
    adsReady: Boolean,
    privacyOptionsAvailable: Boolean,
    onPrivacyOptions: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // A save just landed in MediaStore -> refresh the list (and jump to it).
    val savedCount by VoiceRecorder.savedCount.collectAsState()
    var lastSavedSeen by remember { mutableIntStateOf(0) }
    LaunchedEffect(savedCount) {
        if (savedCount > lastSavedSeen) {
            lastSavedSeen = savedCount
            refreshKey++
            tab = 1
        }
    }

    // No ads while a recording session is active — banner lives on the list screen only.
    val recState by VoiceRecorder.state.collectAsState()
    val showBanner = tab == 1 && adsReady && recState == VoiceRecorder.State.IDLE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TinyVoice") },
                actions = {
                    if (privacyOptionsAvailable) {
                        IconButton(onClick = onPrivacyOptions) {
                            Icon(Icons.Filled.Info, contentDescription = "Privacy options")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (showBanner) BannerAd()
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                        label = { Text("Record") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                        label = { Text("Recordings") }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (tab) {
            0 -> RecordScreen(
                modifier = Modifier.padding(padding),
                snackbarHostState = snackbarHostState
            )
            else -> RecordingsScreen(
                modifier = Modifier.padding(padding),
                refreshKey = refreshKey,
                snackbarHostState = snackbarHostState,
                onChanged = { refreshKey++ }
            )
        }
    }
}

@Composable
private fun RecordScreen(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by VoiceRecorder.state.collectAsState()
    val elapsedMs by VoiceRecorder.elapsedMs.collectAsState()
    val amplitudes by VoiceRecorder.amplitudes.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            RecordingService.startAction(context, RecordingService.ACTION_START)
        } else {
            scope.launch { snackbarHostState.showSnackbar("Microphone permission is needed to record") }
        }
    }

    fun onRecordClick() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            RecordingService.startAction(context, RecordingService.ACTION_START)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = RecordingRepository.formatDuration(elapsedMs),
            style = MaterialTheme.typography.displayMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (state) {
                VoiceRecorder.State.IDLE -> "Tap to record"
                VoiceRecorder.State.RECORDING -> "Recording…"
                VoiceRecorder.State.PAUSED -> "Paused"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Waveform(
            amplitudes = amplitudes,
            active = state != VoiceRecorder.State.IDLE,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
        Spacer(Modifier.height(32.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                VoiceRecorder.State.IDLE -> {
                    FloatingActionButton(
                        onClick = ::onRecordClick,
                        modifier = Modifier.size(88.dp),
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Record",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                VoiceRecorder.State.RECORDING -> {
                    OutlinedButton(onClick = {
                        RecordingService.startAction(context, RecordingService.ACTION_PAUSE)
                    }) {
                        Icon(Icons.Filled.Pause, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Pause")
                    }
                    Button(
                        onClick = {
                            RecordingService.startAction(context, RecordingService.ACTION_STOP)
                        }
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Stop")
                    }
                }
                VoiceRecorder.State.PAUSED -> {
                    OutlinedButton(onClick = {
                        RecordingService.startAction(context, RecordingService.ACTION_RESUME)
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Resume")
                    }
                    Button(
                        onClick = {
                            RecordingService.startAction(context, RecordingService.ACTION_STOP)
                        }
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Stop")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Recordings are saved on this device only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Waveform(
    amplitudes: List<Int>,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val barWidth = 8f
        val gap = 6f
        val maxBars = (size.width / (barWidth + gap)).toInt().coerceAtLeast(1)
        val amps = amplitudes.takeLast(maxBars)
        val maxAmp = 32767f
        for (i in 0 until maxBars) {
            val amp = amps.getOrNull(i) ?: 0
            val h = if (active && amp > 0) {
                (amp / maxAmp * size.height).coerceAtLeast(6f)
            } else {
                6f
            }
            drawRect(
                color = if (active && amp > 0) color else idleColor,
                topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2f),
                size = Size(barWidth, h)
            )
        }
    }
}

@Composable
private fun RecordingsScreen(
    modifier: Modifier = Modifier,
    refreshKey: Int,
    snackbarHostState: SnackbarHostState,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var renameTarget by remember { mutableStateOf<Recording?>(null) }
    var trimTarget by remember { mutableStateOf<Recording?>(null) }
    var deleteTarget by remember { mutableStateOf<Recording?>(null) }

    var playingId by remember { mutableStateOf<Long?>(null) }
    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlayback() {
        try {
            playerRef.value?.stop()
        } catch (_: Exception) {
        }
        try {
            playerRef.value?.release()
        } catch (_: Exception) {
        }
        playerRef.value = null
        playingId = null
    }

    fun togglePlay(rec: Recording) {
        if (playingId == rec.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, rec.uri)
                prepare()
                start()
            }
            mp.setOnCompletionListener { stopPlayback() }
            playerRef.value = mp
            playingId = rec.id
        } catch (_: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Couldn't play this recording") }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    LaunchedEffect(refreshKey) {
        isLoading = true
        recordings = withContext(Dispatchers.IO) { RecordingRepository.list(context) }
        isLoading = false
    }

    fun share(rec: Recording) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, rec.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share recording"))
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isLoading) {
            Text(
                "Loading…",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else if (recordings.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hit Record and your clips will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(recordings, key = { it.id }) { rec ->
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                rec.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${RecordingRepository.formatDuration(rec.durationMs)} · " +
                                    "${RecordingRepository.formatDate(rec.dateAddedSec)} · " +
                                    RecordingRepository.formatBytes(rec.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(onClick = { togglePlay(rec) }) {
                                    Icon(
                                        if (playingId == rec.id) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = if (playingId == rec.id) "Stop" else "Play"
                                    )
                                }
                                IconButton(onClick = { renameTarget = rec }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                                }
                                IconButton(onClick = { trimTarget = rec }) {
                                    Icon(Icons.Filled.ContentCut, contentDescription = "Trim")
                                }
                                IconButton(onClick = { share(rec) }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share")
                                }
                                IconButton(onClick = { deleteTarget = rec }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { rec ->
        RenameDialog(
            recording = rec,
            onDismiss = { renameTarget = null },
            onRenamed = { renameTarget = null; onChanged() }
        )
    }

    trimTarget?.let { rec ->
        TrimDialog(
            recording = rec,
            snackbarHostState = snackbarHostState,
            onDismiss = { trimTarget = null },
            onTrimmed = { trimTarget = null; onChanged() }
        )
    }

    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text("\"${rec.name}\" will be removed from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                RecordingRepository.delete(context, rec)
                            }
                            deleteTarget = null
                            onChanged()
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RenameDialog(
    recording: Recording,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember {
        mutableStateOf(recording.name.removeSuffix(".m4a").removeSuffix(".mp4"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename recording") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) return@Button
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            RecordingRepository.rename(context, recording, trimmed)
                        }
                        onRenamed()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TrimDialog(
    recording: Recording,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onTrimmed: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val durationMs = remember {
        RecordingRepository.durationOf(context, recording.uri)
            .takeIf { it > 0 } ?: recording.durationMs
    }
    val durationSec = (durationMs / 1000).toFloat().coerceAtLeast(1f)
    var startSec by remember { mutableFloatStateOf(0f) }
    var endSec by remember { mutableFloatStateOf(durationSec) }
    var isTrimming by remember { mutableStateOf(false) }

    val previewPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    val previewJob = remember { mutableStateOf<Job?>(null) }

    fun stopPreview() {
        previewJob.value?.cancel()
        previewJob.value = null
        try {
            previewPlayer.value?.stop()
        } catch (_: Exception) {
        }
        try {
            previewPlayer.value?.release()
        } catch (_: Exception) {
        }
        previewPlayer.value = null
    }

    fun previewSelection() {
        stopPreview()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, recording.uri)
                prepare()
                seekTo((startSec * 1000).toInt())
                start()
            }
            previewPlayer.value = mp
            previewJob.value = scope.launch {
                delay(((endSec - startSec) * 1000).toLong().coerceAtLeast(500))
                stopPreview()
            }
        } catch (_: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Couldn't preview") }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    AlertDialog(
        onDismissRequest = { stopPreview(); onDismiss() },
        title = { Text("Trim recording") },
        text = {
            Column {
                Text(
                    "Keep ${RecordingRepository.formatDuration((startSec * 1000).toLong())} – " +
                        RecordingRepository.formatDuration((endSec * 1000).toLong()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text("Start", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = startSec,
                    onValueChange = { startSec = it.coerceAtMost(endSec) },
                    valueRange = 0f..durationSec
                )
                Text("End", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = endSec,
                    onValueChange = { endSec = it.coerceAtLeast(startSec) },
                    valueRange = 0f..durationSec
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = ::previewSelection) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Preview selection")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isTrimming,
                onClick = {
                    scope.launch {
                        isTrimming = true
                        stopPreview()
                        val range = AudioTrimmer.coerceRange(
                            (startSec * 1000).toLong(),
                            (endSec * 1000).toLong(),
                            durationMs
                        )
                        if (range == null) {
                            snackbarHostState.showSnackbar("Select a longer section to trim")
                        } else {
                            val file = withContext(Dispatchers.IO) {
                                AudioTrimmer.trimToFile(context, recording.uri, range)
                            }
                            if (file != null) {
                                val base = recording.name
                                    .removeSuffix(".m4a").removeSuffix(".mp4")
                                withContext(Dispatchers.IO) {
                                    RecordingRepository.saveRecording(
                                        context, file, "$base (trimmed)"
                                    )
                                }
                                file.delete()
                                onTrimmed()
                            } else {
                                snackbarHostState.showSnackbar("Trim failed")
                            }
                        }
                        isTrimming = false
                    }
                }
            ) { Text(if (isTrimming) "Trimming…" else "Save trim") }
        },
        dismissButton = {
            TextButton(onClick = { stopPreview(); onDismiss() }) { Text("Cancel") }
        }
    )
}
