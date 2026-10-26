package com.example.mediadownloader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DownloaderScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen() {
    val context = LocalContext.current

    // State variables
    var urlInput by remember { mutableStateOf("") }
    var isMp3Only by remember { mutableStateOf(false) }

    val videoQualities = listOf("4K (2160p)", "1080p (Full HD)", "720p (HD)", "480p")
    val audioQualities = listOf("320 kbps (Best)", "192 kbps (High)", "128 kbps (Standard)")

    var selectedVideoQuality by remember { mutableStateOf(videoQualities[1]) } // Default 1080p
    var selectedAudioQuality by remember { mutableStateOf(audioQualities[0]) } // Default 320kbps

    // Directory selection state
    var selectedDirectoryUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDirectoryName by remember { mutableStateOf("Default (Downloads Folder)") }

    // Folder picker launcher using Android's Storage Access Framework (SAF)
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permissions across restarts
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            selectedDirectoryUri = it
            val docFile = DocumentFile.fromTreeUri(context, it)
            selectedDirectoryName = docFile?.name ?: it.lastPathSegment ?: "Custom Folder"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Downloader") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Media URL Input
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Video URL") },
                placeholder = { Text("https://...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 2. MP3 Only Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "MP3 Audio Only",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Extract audio track without video",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = isMp3Only,
                        onCheckedChange = { isMp3Only = it }
                    )
                }
            }

            // 3. Quality Selector (Changes based on MP3 toggle)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isMp3Only) "Select Audio Quality" else "Select Video Quality",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val options = if (isMp3Only) audioQualities else videoQualities
                val currentSelection = if (isMp3Only) selectedAudioQuality else selectedVideoQuality

                options.forEach { quality ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .selectable(
                                selected = (quality == currentSelection),
                                onClick = {
                                    if (isMp3Only) {
                                        selectedAudioQuality = quality
                                    } else {
                                        selectedVideoQuality = quality
                                    }
                                },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (quality == currentSelection),
                            onClick = null // Handled by Row's selectable modifier
                        )
                        Text(
                            text = quality,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }

            // 4. Download Location Selector
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download Location",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = selectedDirectoryName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    FilledTonalButton(
                        onClick = { directoryPickerLauncher.launch(null) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Choose Folder",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 5. Download Action Button
            Button(
                onClick = {
                    if (urlInput.isBlank()) {
                        Toast.makeText(context, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    val chosenQuality = if (isMp3Only) selectedAudioQuality else selectedVideoQuality
                    startMediaDownload(
                        context = context,
                        sourceUrl = urlInput.trim(),
                        isMp3 = isMp3Only,
                        quality = chosenQuality,
                        destinationFolderName = selectedDirectoryName
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = if (isMp3Only) "Download MP3" else "Download Video ($selectedVideoQuality)",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

/**
 * Initiates a download using Android's system DownloadManager.
 * Note: For adaptive streams (like 1080p/4K where video and audio tracks are separate)
 * or MP3 conversion, point `downloadEndpoint` to your media processing backend or direct stream URL.
 */
fun startMediaDownload(
    context: Context,
    sourceUrl: String,
    isMp3: Boolean,
    quality: String,
    destinationFolderName: String
) {
    try {
        val extension = if (isMp3) "mp3" else "mp4"
        val cleanQuality = quality.substringBefore(" ").lowercase()
        val fileName = "media_${System.currentTimeMillis()}_${cleanQuality}.$extension"

        // If using a backend API that resolves/transcodes the URL based on parameters,
        // construct the request URI here. Otherwise, use the direct media URL.
        val requestUri = Uri.parse(sourceUrl)

        val request = DownloadManager.Request(requestUri).apply {
            setTitle("Downloading $fileName")
            setDescription("Format: ${extension.uppercase()} | Quality: $quality")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)

            // Save to public Downloads or Music directory with subfolder
            val baseDir = if (isMp3) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS
            val subPath = if (destinationFolderName == "Default (Downloads Folder)") {
                fileName
            } else {
                "$destinationFolderName/$fileName"
            }
            setDestinationInExternalPublicDir(baseDir, subPath)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)

        Toast.makeText(
            context,
            "Download started: $fileName",
            Toast.LENGTH_LONG
        ).show()

    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Failed to start download: ${e.localizedMessage}",
            Toast.LENGTH_LONG
        ).show()
    }
}