package com.example.mediadownloader

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Patterns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PREFS_NAME = "downloader_prefs"
private const val KEY_IS_MP3 = "is_mp3_only"
private const val KEY_VIDEO_QUALITY = "video_quality"
private const val KEY_AUDIO_QUALITY = "audio_quality"
private const val KEY_CUSTOM_DIR_URI = "custom_dir_uri"
private const val KEY_CUSTOM_DIR_NAME = "custom_dir_name"
private const val DEFAULT_DIR_LABEL = "Default (Public Downloads)"

class MainActivity : ComponentActivity() {

    private var incomingSharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleShareIntent(intent)

        setContent {
            ResponsiveAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DownloaderScreen(
                        sharedUrl = incomingSharedUrl,
                        onSharedUrlConsumed = { incomingSharedUrl = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                incomingSharedUrl = extractCleanUrl(sharedText)
            }
        }
    }

    private fun extractCleanUrl(rawText: String): String {
        val matcher = Patterns.WEB_URL.matcher(rawText)
        while (matcher.find()) {
            val match = matcher.group()
            if (match.startsWith("http://", ignoreCase = true) ||
                match.startsWith("https://", ignoreCase = true)
            ) {
                return match
            }
        }
        return rawText.trim()
    }
}

@Composable
fun ResponsiveAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

data class QualityOption(
    val id: String,
    val title: String,
    val subtitle: String
)

/**
 * Checks whether our app still holds read/write SAF permission for a saved folder URI.
 */
private fun hasValidUriPermission(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    val targetUri = Uri.parse(uriString)
    return context.contentResolver.persistedUriPermissions.any { perm ->
        perm.uri == targetUri && perm.isReadPermission && perm.isWritePermission
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    sharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Engine state
    var isEngineReady by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Initializing yt-dlp & FFmpeg...") }

    // Load initial values from SharedPreferences
    var urlInput by rememberSaveable { mutableStateOf("") }
    var isMp3Only by rememberSaveable {
        mutableStateOf(prefs.getBoolean(KEY_IS_MP3, false))
    }
    var selectedVideoId by rememberSaveable {
        mutableStateOf(prefs.getString(KEY_VIDEO_QUALITY, "1080p") ?: "1080p")
    }
    var selectedAudioId by rememberSaveable {
        mutableStateOf(prefs.getString(KEY_AUDIO_QUALITY, "320K") ?: "320K")
    }

    // Verify saved custom directory URI still has valid SAF permissions
    val initialUriString = remember {
        val savedUri = prefs.getString(KEY_CUSTOM_DIR_URI, null)
        if (hasValidUriPermission(context, savedUri)) savedUri else null
    }
    val initialDirName = remember {
        if (initialUriString != null) {
            prefs.getString(KEY_CUSTOM_DIR_NAME, "Custom Folder") ?: "Custom Folder"
        } else {
            DEFAULT_DIR_LABEL
        }
    }

    var selectedDirectoryUriString by rememberSaveable { mutableStateOf(initialUriString) }
    var selectedDirectoryName by rememberSaveable { mutableStateOf(initialDirName) }
    val selectedDirectoryUri = remember(selectedDirectoryUriString) {
        selectedDirectoryUriString?.let { Uri.parse(it) }
    }

    // Populate URL when shared from YouTube or another app
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            urlInput = sharedUrl
            Toast.makeText(context, "Shared link loaded", Toast.LENGTH_SHORT).show()
            onSharedUrlConsumed()
        }
    }

    val videoQualities = remember {
        listOf(
            QualityOption("4K", "4K Ultra HD", "2160p MP4"),
            QualityOption("1080p", "1080p Full HD", "Best Balance"),
            QualityOption("720p", "720p HD", "Fast & Compact"),
            QualityOption("480p", "480p SD", "Data Saver")
        )
    }

    val audioQualities = remember {
        listOf(
            QualityOption("320K", "320 kbps", "Studio MP3"),
            QualityOption("192K", "192 kbps", "High Quality"),
            QualityOption("128K", "128 kbps", "Standard Size"),
            QualityOption("96K", "96 kbps", "Voice / Compact")
        )
    }

    // Download progress state
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = (downloadProgress / 100f).coerceIn(0f, 1f),
        label = "DownloadProgress"
    )

    // Initialize YoutubeDL and FFmpeg on launch
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                withContext(Dispatchers.Main) {
                    isEngineReady = true
                    statusMessage = "Ready to download"
                }
            } catch (e: YoutubeDLException) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Engine init error: ${e.localizedMessage}"
                }
            }
        }
    }

    // Storage Access Framework Folder Picker + SharedPreferences Save
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            val docFile = DocumentFile.fromTreeUri(context, it)
            val resolvedName = docFile?.name ?: it.lastPathSegment ?: "Custom Folder"

            selectedDirectoryUriString = it.toString()
            selectedDirectoryName = resolvedName

            prefs.edit()
                .putString(KEY_CUSTOM_DIR_URI, it.toString())
                .putString(KEY_CUSTOM_DIR_NAME, resolvedName)
                .apply()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Media Downloader",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    IconButton(
                        enabled = isEngineReady && !isDownloading,
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) {
                                    statusMessage = "Updating extractor engine..."
                                }
                                try {
                                    val status = YoutubeDL.getInstance().updateYoutubeDL(
                                        context.applicationContext,
                                        YoutubeDL.UpdateChannel._STABLE
                                    )
                                    withContext(Dispatchers.Main) {
                                        statusMessage = "Extractor: ${status?.name ?: "Up to date"}"
                                        Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        statusMessage = "Update failed: ${e.localizedMessage}"
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Update Extractor"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            enabled = isEngineReady && !isDownloading,
                            onClick = {
                                if (urlInput.isBlank()) {
                                    Toast.makeText(context, "Please paste or share a video link first", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                executeYtDlpDownload(
                                    context = context,
                                    scope = coroutineScope,
                                    url = urlInput.trim(),
                                    isMp3 = isMp3Only,
                                    videoQuality = selectedVideoId,
                                    audioQuality = selectedAudioId,
                                    customDirUri = selectedDirectoryUri,
                                    onStart = {
                                        isDownloading = true
                                        downloadProgress = 0f
                                        statusMessage = "Fetching stream info..."
                                    },
                                    onProgress = { progress, eta, line ->
                                        if (progress >= 0f) downloadProgress = progress
                                        statusMessage = "${progress.toInt()}% • ETA ${eta}s • $line"
                                    },
                                    onComplete = { message ->
                                        isDownloading = false
                                        downloadProgress = 100f
                                        statusMessage = message
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isMp3Only) {
                                    "Download MP3 ($selectedAudioId)"
                                } else {
                                    "Download Video ($selectedVideoId)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        AnimatedVisibility(visible = isDownloading) {
                            FilledTonalButton(
                                onClick = {
                                    YoutubeDL.getInstance().destroyProcessById("active_download")
                                    isDownloading = false
                                    statusMessage = "Download cancelled"
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.height(54.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. URL Input Card with Quick Paste
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Media Link",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            FilledTonalButton(
                                enabled = !isDownloading,
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text
                                    if (!clipText.isNullOrBlank()) {
                                        urlInput = clipText.trim()
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Paste", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = { Text("Share from app or paste link here...") },
                            singleLine = true,
                            enabled = !isDownloading,
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = {
                                if (urlInput.isNotEmpty() && !isDownloading) {
                                    IconButton(onClick = { urlInput = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear URL"
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 2. Format Mode Selector (Saved automatically to SharedPreferences)
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Format Type",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FormatModeCard(
                                title = "Video (MP4)",
                                subtitle = "Video + Audio",
                                icon = Icons.Default.Videocam,
                                selected = !isMp3Only,
                                enabled = !isDownloading,
                                onClick = {
                                    isMp3Only = false
                                    prefs.edit().putBoolean(KEY_IS_MP3, false).apply()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            FormatModeCard(
                                title = "Audio (MP3)",
                                subtitle = "Music / Podcast",
                                icon = Icons.Default.MusicNote,
                                selected = isMp3Only,
                                enabled = !isDownloading,
                                onClick = {
                                    isMp3Only = true
                                    prefs.edit().putBoolean(KEY_IS_MP3, true).apply()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 3. Responsive 2x2 Quality Selector Grid (Saved automatically to SharedPreferences)
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isMp3Only) "Audio Bitrate" else "Video Resolution",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val activeOptions = if (isMp3Only) audioQualities else videoQualities
                        val currentSelectedId = if (isMp3Only) selectedAudioId else selectedVideoId

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            activeOptions.chunked(2).forEach { rowOptions ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowOptions.forEach { option ->
                                        QualityGridCard(
                                            option = option,
                                            selected = (option.id == currentSelectedId),
                                            enabled = !isDownloading,
                                            onClick = {
                                                if (isMp3Only) {
                                                    selectedAudioId = option.id
                                                    prefs.edit().putString(KEY_AUDIO_QUALITY, option.id).apply()
                                                } else {
                                                    selectedVideoId = option.id
                                                    prefs.edit().putString(KEY_VIDEO_QUALITY, option.id).apply()
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Download Location Card (Saved automatically + Reset to Default option)
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Save Location",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = selectedDirectoryName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (selectedDirectoryUriString != null) {
                                IconButton(
                                    enabled = !isDownloading,
                                    onClick = {
                                        selectedDirectoryUriString = null
                                        selectedDirectoryName = DEFAULT_DIR_LABEL
                                        prefs.edit()
                                            .remove(KEY_CUSTOM_DIR_URI)
                                            .remove(KEY_CUSTOM_DIR_NAME)
                                            .apply()
                                        Toast.makeText(context, "Reset to Default Downloads", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Restore,
                                        contentDescription = "Reset to Default Folder"
                                    )
                                }
                            }

                            FilledTonalButton(
                                enabled = !isDownloading,
                                onClick = { directoryPickerLauncher.launch(null) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Change")
                            }
                        }
                    }
                }

                // 5. Live Status & Progress Card
                OutlinedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isDownloading) "Downloading & Processing" else "Status",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isDownloading) {
                                Text(
                                    text = "${downloadProgress.toInt().coerceIn(0, 100)}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (isDownloading) {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }

                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatModeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QualityGridCard(
    option: QualityOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = option.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun executeYtDlpDownload(
    context: Context,
    scope: CoroutineScope,
    url: String,
    isMp3: Boolean,
    videoQuality: String,
    audioQuality: String,
    customDirUri: Uri?,
    onStart: () -> Unit,
    onProgress: (Float, Long, String) -> Unit,
    onComplete: (String) -> Unit
) {
    onStart()

    scope.launch(Dispatchers.IO) {
        val workingDir = File(context.cacheDir, "ytdlp_staging").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-mtime")
            request.addOption("-o", "${workingDir.absolutePath}/%(title).150s.%(ext)s")

            if (isMp3) {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", audioQuality)
            } else {
                val maxHeight = when (videoQuality) {
                    "4K" -> "2160"
                    "1080p" -> "1080"
                    "720p" -> "720"
                    else -> "480"
                }
                request.addOption(
                    "-f",
                    "bestvideo[height<=$maxHeight]+bestaudio/best[height<=$maxHeight]"
                )
                request.addOption("--merge-output-format", "mp4")
            }

            YoutubeDL.getInstance().execute(
                request,
                "active_download"
            ) { progress, etaInSeconds, line ->
                scope.launch(Dispatchers.Main) {
                    onProgress(progress, etaInSeconds, line)
                }
            }

            val generatedFile = workingDir.listFiles()?.firstOrNull()
                ?: throw IllegalStateException("No output file produced")

            val savedPathInfo = if (customDirUri != null) {
                copyFileToCustomUri(context, generatedFile, customDirUri)
            } else {
                moveFileToPublicDownloads(context, generatedFile)
            }

            workingDir.deleteRecursively()

            withContext(Dispatchers.Main) {
                onComplete("Saved to $savedPathInfo")
            }
        } catch (e: Exception) {
            workingDir.deleteRecursively()
            withContext(Dispatchers.Main) {
                onComplete("Error: ${e.localizedMessage ?: "Download failed"}")
            }
        }
    }
}

private fun copyFileToCustomUri(context: Context, sourceFile: File, treeUri: Uri): String {
    val targetDir = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalStateException("Cannot access selected folder")

    val extension = sourceFile.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: if (extension == "mp3") "audio/mpeg" else "video/mp4"

    val destDoc = targetDir.createFile(mimeType, sourceFile.name)
        ?: throw IllegalStateException("Failed to create file in selected folder")

    context.contentResolver.openOutputStream(destDoc.uri)?.use { outputStream ->
        sourceFile.inputStream().use { inputStream ->
            inputStream.copyTo(outputStream)
        }
    }
    return "${targetDir.name}/${sourceFile.name}"
}

private fun moveFileToPublicDownloads(context: Context, sourceFile: File): String {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.exists()) downloadsDir.mkdirs()

    val destFile = File(downloadsDir, sourceFile.name)
    sourceFile.inputStream().use { input ->
        destFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
    return "Downloads/${destFile.name}"
}