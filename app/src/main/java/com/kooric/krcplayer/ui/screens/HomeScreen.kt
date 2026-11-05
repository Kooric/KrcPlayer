package com.kooric.krcplayer.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kooric.krcplayer.data.RecentHistoryManager
import com.kooric.krcplayer.data.SecretVaultManager
import com.kooric.krcplayer.data.VideoScanner
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.components.RecentVideoCard
import com.kooric.krcplayer.ui.components.VideoGridItemCard
import com.kooric.krcplayer.ui.components.VideoItemCard
import com.kooric.krcplayer.ui.theme.*
import kotlinx.coroutines.launch

enum class VideoSortOption(val label: String) {
    DATE_DESC("新しい順"),
    DATE_ASC("古い順"),
    NAME_ASC("名前順"),
    SIZE_DESC("サイズ順"),
    DURATION_DESC("再生時間順")
}

private fun formatTotalSize(totalBytes: Long): String {
    val kb = totalBytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", kb)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    currentlyPlayingVideoId: Long?,
    recentRefreshTick: Int,
    onPlayVideo: (VideoItem) -> Unit,
    onOpenSecretVault: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vaultManager = remember { SecretVaultManager(context) }
    val historyManager = remember { RecentHistoryManager(context) }

    var allVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var sortOption by remember { mutableStateOf(VideoSortOption.DATE_DESC) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var isGridView by remember { mutableStateOf(true) }

    var selectedVideoIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isProcessingAction by remember { mutableStateOf(false) }
    val isSelectionMode = selectedVideoIds.isNotEmpty()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    fun reloadVideos() {
        coroutineScope.launch {
            isLoading = true
            allVideos = VideoScanner.getAllVideos(context)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        reloadVideos()
    }

    BackHandler(enabled = isSelectionMode) {
        selectedVideoIds = emptySet()
    }

    // 全動画の総容量
    val totalStorageText = remember(allVideos) {
        val totalBytes = allVideos.sumOf { it.size }
        "全 ${allVideos.size} 本 • 総計 ${formatTotalSize(totalBytes)}"
    }

    // 最近再生した動画（最大5件）
    val recentVideos = remember(allVideos, recentRefreshTick, currentlyPlayingVideoId) {
        historyManager.getRecentVideos(allVideos)
    }

    val displayedVideos = remember(allVideos, sortOption, searchQuery) {
        val filtered = if (searchQuery.isBlank()) {
            allVideos
        } else {
            allVideos.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }

        when (sortOption) {
            VideoSortOption.DATE_DESC -> filtered.sortedByDescending { it.dateModified }
            VideoSortOption.DATE_ASC -> filtered.sortedBy { it.dateModified }
            VideoSortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            VideoSortOption.SIZE_DESC -> filtered.sortedByDescending { it.size }
            VideoSortOption.DURATION_DESC -> filtered.sortedByDescending { it.duration }
        }
    }

    Scaffold(
        containerColor = PureBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureBlack)
                    .statusBarsPadding()
            ) {
                AnimatedContent(
                    targetState = isSelectionMode,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 }) togetherWith
                                (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
                    },
                    label = "TopBarMode"
                ) { selectionActive ->
                    if (selectionActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .background(DeepBlueSurface.copy(alpha = 0.65f))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selectedVideoIds = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                            }

                            Text(
                                text = "${selectedVideoIds.size} 件選択中",
                                color = ElectricBlueGlow,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )

                            TextButton(
                                onClick = {
                                    selectedVideoIds = if (selectedVideoIds.size == displayedVideos.size) {
                                        emptySet()
                                    } else {
                                        displayedVideos.map { it.id }.toSet()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SelectAll,
                                    contentDescription = null,
                                    tint = ElectricBlueGlow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (selectedVideoIds.size == displayedVideos.size) "全解除" else "すべて選択",
                                    color = ElectricBlueGlow,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(ElectricBlue, ElectricBlueGlow)
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "KrcPlayer",
                                    color = TextPrimary,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = totalStorageText,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            IconButton(onClick = { isGridView = !isGridView }) {
                                Icon(
                                    imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                    contentDescription = "Toggle View",
                                    tint = ElectricBlueGlow
                                )
                            }

                            IconButton(onClick = { isSearching = !isSearching }) {
                                Icon(
                                    imageVector = if (isSearching) Icons.Default.SearchOff else Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = TextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // シークレットボタン（枠線と背景色の範囲が1pxのズレもなく完全一致するBox構造）
                            val pillShape = RoundedCornerShape(50)
                            Row(
                                modifier = Modifier
                                    .clip(pillShape)
                                    .background(DeepBlueSurface)
                                    .border(1.dp, ElectricBlueBright.copy(alpha = 0.75f), pillShape)
                                    .clickable { onOpenSecretVault() }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Secret Vault",
                                    tint = ElectricBlueGlow,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "シークレット",
                                    color = ElectricBlueGlow,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isSearching && !isSelectionMode,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("動画タイトルを検索...", color = TextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = ElectricBlueGlow) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = null, tint = TextSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlueBright,
                            unfocusedBorderColor = CardBorder,
                            focusedContainerColor = SurfaceDark,
                            unfocusedContainerColor = SurfaceDark
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                // ソートチップバー
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoSortOption.entries.forEach { option ->
                        val selected = sortOption == option
                        val chipBg by animateColorAsState(
                            targetValue = if (selected) ElectricBlue else SurfaceDark,
                            animationSpec = tween(200),
                            label = "ChipBg"
                        )
                        val chipBorder by animateColorAsState(
                            targetValue = if (selected) ElectricBlueGlow else CardBorder,
                            animationSpec = tween(200),
                            label = "ChipBorder"
                        )
                        val chipShape = RoundedCornerShape(50)
                        Box(
                            modifier = Modifier
                                .clip(chipShape)
                                .background(chipBg)
                                .border(1.dp, chipBorder, chipShape)
                                .clickable { sortOption = option }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = option.label,
                                color = if (selected) Color.White else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                HorizontalDivider(color = CardBorder.copy(alpha = 0.4f), thickness = 1.dp)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(PureBlack)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = ElectricBlueBright,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (displayedVideos.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = ElectricBlue.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "動画が見つかりませんでした",
                        color = TextSecondary,
                        fontSize = 15.sp
                    )
                }
            } else {
                // グリッド ⇔ リスト表示の滑らかなクロスフェード遷移
                AnimatedContent(
                    targetState = isGridView,
                    transitionSpec = {
                        fadeIn(tween(240)) togetherWith fadeOut(tween(180))
                    },
                    label = "ViewModeTransition"
                ) { gridMode ->
                    if (gridMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 10.dp,
                                end = 10.dp,
                                top = 10.dp,
                                bottom = if (isSelectionMode) 110.dp else 160.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (recentVideos.isNotEmpty() && searchQuery.isBlank()) {
                                item(span = { GridItemSpan(3) }) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = null,
                                                tint = ElectricBlueGlow,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "最近再生した動画",
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                                        ) {
                                            items(recentVideos, key = { "recent_${it.id}" }) { recentVideo ->
                                                val isRecentSelected = selectedVideoIds.contains(recentVideo.id)
                                                RecentVideoCard(
                                                    video = recentVideo,
                                                    isSelectionMode = isSelectionMode,
                                                    isSelected = isRecentSelected,
                                                    isCurrentlyPlaying = currentlyPlayingVideoId == recentVideo.id,
                                                    onClick = {
                                                        if (isSelectionMode) {
                                                            selectedVideoIds = if (isRecentSelected) {
                                                                selectedVideoIds - recentVideo.id
                                                            } else {
                                                                selectedVideoIds + recentVideo.id
                                                            }
                                                        } else {
                                                            onPlayVideo(recentVideo)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        selectedVideoIds = selectedVideoIds + recentVideo.id
                                                    }
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        HorizontalDivider(color = CardBorder.copy(alpha = 0.4f), thickness = 1.dp)
                                    }
                                }
                            }

                            items(
                                items = displayedVideos,
                                key = { it.id }
                            ) { video ->
                                val isSelected = selectedVideoIds.contains(video.id)
                                VideoGridItemCard(
                                    video = video,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = isSelected,
                                    isCurrentlyPlaying = currentlyPlayingVideoId == video.id,
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedVideoIds = if (isSelected) {
                                                selectedVideoIds - video.id
                                            } else {
                                                selectedVideoIds + video.id
                                            }
                                        } else {
                                            onPlayVideo(video)
                                        }
                                    },
                                    onLongClick = {
                                        selectedVideoIds = selectedVideoIds + video.id
                                    },
                                    modifier = Modifier.animateItemPlacement(
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                    )
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = if (isSelectionMode) 110.dp else 160.dp
                            )
                        ) {
                            if (recentVideos.isNotEmpty() && searchQuery.isBlank()) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = null,
                                                tint = ElectricBlueGlow,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "最近再生した動画",
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                        ) {
                                            items(recentVideos, key = { "recent_list_${it.id}" }) { recentVideo ->
                                                val isRecentSelected = selectedVideoIds.contains(recentVideo.id)
                                                RecentVideoCard(
                                                    video = recentVideo,
                                                    isSelectionMode = isSelectionMode,
                                                    isSelected = isRecentSelected,
                                                    isCurrentlyPlaying = currentlyPlayingVideoId == recentVideo.id,
                                                    onClick = {
                                                        if (isSelectionMode) {
                                                            selectedVideoIds = if (isRecentSelected) {
                                                                selectedVideoIds - recentVideo.id
                                                            } else {
                                                                selectedVideoIds + recentVideo.id
                                                            }
                                                        } else {
                                                            onPlayVideo(recentVideo)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        selectedVideoIds = selectedVideoIds + recentVideo.id
                                                    }
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        HorizontalDivider(color = CardBorder.copy(alpha = 0.4f), thickness = 1.dp)
                                    }
                                }
                            }

                            items(
                                items = displayedVideos,
                                key = { it.id }
                            ) { video ->
                                val isSelected = selectedVideoIds.contains(video.id)
                                VideoItemCard(
                                    video = video,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = isSelected,
                                    isCurrentlyPlaying = currentlyPlayingVideoId == video.id,
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedVideoIds = if (isSelected) {
                                                selectedVideoIds - video.id
                                            } else {
                                                selectedVideoIds + video.id
                                            }
                                        } else {
                                            onPlayVideo(video)
                                        }
                                    },
                                    onLongClick = {
                                        selectedVideoIds = selectedVideoIds + video.id
                                    },
                                    modifier = Modifier.animateItemPlacement(
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ===== 長押し選択時の下部フローティング一括操作バー =====
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                    initialOffsetY = { it }
                ) + fadeIn(),
                exit = slideOutVertically(
                    animationSpec = tween(200),
                    targetOffsetY = { it }
                ) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(24.dp, RoundedCornerShape(20.dp), spotColor = ElectricBlue)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceElevated)
                        .border(1.dp, ElectricBlueBright.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        enabled = !isProcessingAction,
                        onClick = {
                            if (isProcessingAction) return@Button
                            isProcessingAction = true
                            val targets = allVideos.filter { selectedVideoIds.contains(it.id) }
                            selectedVideoIds = emptySet()
                            coroutineScope.launch {
                                val count = vaultManager.moveVideosToVault(targets)
                                Toast.makeText(
                                    context,
                                    "${count}件の動画をシークレットへ移動しました",
                                    Toast.LENGTH_SHORT
                                ).show()
                                reloadVideos()
                                isProcessingAction = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricBlueBright,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "シークレットへ移動 (${selectedVideoIds.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        enabled = !isProcessingAction,
                        onClick = { showDeleteConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed.copy(alpha = 0.2f),
                            contentColor = DangerRed
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .border(1.dp, DangerRed.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "削除",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            title = { Text("動画の一括削除", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "選択した ${selectedVideoIds.size} 件の動画を削除しますか？",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targets = allVideos.filter { selectedVideoIds.contains(it.id) }
                        showDeleteConfirmDialog = false
                        selectedVideoIds = emptySet()
                        coroutineScope.launch {
                            val count = VideoScanner.deleteVideos(context, targets)
                            Toast.makeText(context, "${count}件の動画を削除しました", Toast.LENGTH_SHORT).show()
                            reloadVideos()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("削除する", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("キャンセル", color = TextSecondary)
                }
            }
        )
    }
}
