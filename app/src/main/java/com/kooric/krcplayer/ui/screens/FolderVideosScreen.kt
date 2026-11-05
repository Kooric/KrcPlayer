package com.kooric.krcplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kooric.krcplayer.data.SecretVaultManager
import com.kooric.krcplayer.data.VideoScanner
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.components.VideoItemCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderVideosScreen(
    folderName: String,
    onBack: () -> Unit,
    onPlayVideo: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vaultManager = remember { SecretVaultManager(context) }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    LaunchedEffect(folderName) {
        val all = VideoScanner.getAllVideos(context)
        videos = all.filter { it.folderName == folderName }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            items(videos) { video ->
                VideoItemCard(
                    video = video,
                    onClick = { onPlayVideo(video.uri.toString()) },
                    onMoveToSecret = {
                        coroutineScope.launch {
                            val ok = vaultManager.moveToVault(video.uri, video.title)
                            if (ok) {
                                Toast.makeText(context, "シークレットへ移動しました", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }
}
