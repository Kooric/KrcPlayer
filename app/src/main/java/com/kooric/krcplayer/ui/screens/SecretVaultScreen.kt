package com.kooric.krcplayer.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kooric.krcplayer.data.SecretVaultManager
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.components.VideoGridItemCard
import com.kooric.krcplayer.ui.components.VideoItemCard
import com.kooric.krcplayer.ui.theme.*
import kotlinx.coroutines.launch

private fun formatSecretTotalSize(totalBytes: Long): String {
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
fun SecretVaultScreen(
    currentlyPlayingVideoId: Long?,
    onBack: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val vaultManager = remember { SecretVaultManager(context) }

    var isUnlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    val isPinAlreadySet = remember { vaultManager.isPinSet() }
    var secretVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // メインメニューと同一のソート・検索・グリッド/リスト切替ステート
    var sortOption by remember { mutableStateOf(VideoSortOption.DATE_DESC) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(true) }

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val isSelectionMode = selectedIds.isNotEmpty()

    var hasAllFilesAccess by remember { mutableStateOf(SecretVaultManager.isAllFilesAccessGranted()) }

    fun refreshSecretVideos() {
        coroutineScope.launch {
            isLoading = true
            hasAllFilesAccess = SecretVaultManager.isAllFilesAccessGranted()
            secretVideos = vaultManager.getSecretVideos()
            isLoading = false
        }
    }

    // 設定画面から戻ってきたときに権限状態を自動更新して暗号化マイグレーションを実行
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = SecretVaultManager.isAllFilesAccessGranted()
                if (nowGranted != hasAllFilesAccess) {
                    hasAllFilesAccess = nowGranted
                    if (isUnlocked) {
                        refreshSecretVideos()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 指紋認証（生体認証）が端末で利用可能かチェック
    val canUseBiometric = remember(context) {
        val biometricManager = BiometricManager.from(context)
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // 指紋認証プロンプトを起動する関数
    fun launchBiometricAuth() {
        val fragmentActivity = context as? FragmentActivity ?: return
        if (!canUseBiometric || !isPinAlreadySet) return

        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            fragmentActivity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isUnlocked = true
                    refreshSecretVideos()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("シークレットフォルダのロック解除")
            .setSubtitle("指紋認証または生体認証でロックを解除します")
            .setNegativeButtonText("PINコードで入力")
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (_: Exception) {
        }
    }

    // 画面を開いた瞬間に自動で指紋認証を起動
    LaunchedEffect(Unit) {
        if (isPinAlreadySet && canUseBiometric && !isUnlocked) {
            launchBiometricAuth()
        }
    }

    // 4文字入力された瞬間に自動判定して即座にロック解除する関数
    fun onDigitEntered(newPin: String) {
        if (newPin.length > 4) return
        pinInput = newPin

        if (newPin.length == 4) {
            if (!isPinAlreadySet) {
                vaultManager.setPin(newPin)
                Toast.makeText(context, "PINコードを設定しました", Toast.LENGTH_SHORT).show()
                isUnlocked = true
                refreshSecretVideos()
            } else {
                if (vaultManager.verifyPin(newPin)) {
                    isUnlocked = true
                    refreshSecretVideos()
                } else {
                    Toast.makeText(context, "PINコードが違います", Toast.LENGTH_SHORT).show()
                    pinInput = ""
                }
            }
        }
    }

    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }

    val totalSecretStorageText = remember(secretVideos) {
        val totalBytes = secretVideos.sumOf { it.size }
        "全 ${secretVideos.size} 本 • 総計 ${formatSecretTotalSize(totalBytes)}"
    }

    val displayedSecretVideos = remember(secretVideos, sortOption, searchQuery) {
        val filtered = if (searchQuery.isBlank()) {
            secretVideos
        } else {
            secretVideos.filter { it.title.contains(searchQuery, ignoreCase = true) }
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
                if (!isUnlocked) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                        Text(
                            text = "シークレットフォルダ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary
                        )
                    }
                } else {
                    // ===== ロック解除後：メインメニューと完全に同じレイアウトのトップバー =====
                    AnimatedContent(
                        targetState = isSelectionMode,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 }) togetherWith
                                    (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
                        },
                        label = "SecretTopBarMode"
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
                                IconButton(onClick = { selectedIds = emptySet() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                                }

                                Text(
                                    text = "${selectedIds.size} 件選択中",
                                    color = ElectricBlueGlow,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )

                                TextButton(
                                    onClick = {
                                        selectedIds = if (selectedIds.size == displayedSecretVideos.size) {
                                            emptySet()
                                        } else {
                                            displayedSecretVideos.map { it.id }.toSet()
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
                                        text = if (selectedIds.size == displayedSecretVideos.size) "全解除" else "すべて選択",
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
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = ElectricBlueGlow,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "シークレットフォルダ",
                                            color = TextPrimary,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    Text(
                                        text = totalSecretStorageText,
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
                            }
                        }
                    }

                    // 検索バー
                    AnimatedVisibility(
                        visible = isSearching && !isSelectionMode,
                        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("シークレット動画を検索...", color = TextSecondary) },
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

                    // ソートチップバー（メインメニューと同一）
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
                                label = "SecretChipBg"
                            )
                            val chipBorder by animateColorAsState(
                                targetValue = if (selected) ElectricBlueGlow else CardBorder,
                                animationSpec = tween(200),
                                label = "SecretChipBorder"
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(PureBlack)
        ) {
            if (!isUnlocked) {
                // ===== 4桁入力で即時解除 ＆ 指紋認証対応のPINパッド画面 =====
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(DeepBlueSurface, CircleShape)
                            .border(1.5.dp, ElectricBlueBright, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (canUseBiometric && isPinAlreadySet) Icons.Default.Fingerprint else Icons.Default.Lock,
                            contentDescription = "Lock",
                            modifier = Modifier.size(32.dp),
                            tint = ElectricBlueGlow
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isPinAlreadySet) "4桁のPIN または 指紋認証" else "新規4桁PINコードを設定",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) { index ->
                            val filled = index < pinInput.length
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(if (filled) ElectricBlueBright else SurfaceElevated)
                                    .border(
                                        width = 1.5.dp,
                                        color = if (filled) ElectricBlueGlow else CardBorder,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(36.dp))

                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("BIO", "0", "DEL")
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        rows.forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                row.forEach { key ->
                                    when (key) {
                                        "BIO" -> {
                                            if (canUseBiometric && isPinAlreadySet) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(72.dp)
                                                        .clip(CircleShape)
                                                        .background(DeepBlueSurface)
                                                        .border(1.dp, ElectricBlueBright, CircleShape)
                                                        .clickable { launchBiometricAuth() },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Fingerprint,
                                                        contentDescription = "指紋認証",
                                                        tint = ElectricBlueGlow,
                                                        modifier = Modifier.size(30.dp)
                                                    )
                                                }
                                            } else {
                                                Spacer(modifier = Modifier.size(72.dp))
                                            }
                                        }
                                        "DEL" -> {
                                            Box(
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .clip(CircleShape)
                                                    .background(SurfaceDark)
                                                    .border(1.dp, CardBorder, CircleShape)
                                                    .clickable {
                                                        if (pinInput.isNotEmpty()) {
                                                            pinInput = pinInput.dropLast(1)
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                                    contentDescription = "Delete",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        else -> {
                                            Box(
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .clip(CircleShape)
                                                    .background(SurfaceElevated)
                                                    .border(1.dp, ElectricBlue.copy(alpha = 0.4f), CircleShape)
                                                    .clickable {
                                                        onDigitEntered(pinInput + key)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = key,
                                                    color = TextPrimary,
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ===== ロック解除後の一覧画面 =====
                Column(modifier = Modifier.fillMaxSize()) {
                    // もし「全ファイルアクセス権限」がまだOFFの場合は、外部ツール完全暗号化を有効化するワンタップバナーを表示
                    if (!hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(DeepBlueSurface)
                                .border(1.dp, ElectricBlueBright, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = ElectricBlueGlow,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "外部ツールからの完全暗号化を有効化",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "他のアプリやギャラリーから元動画を完全に隠蔽・暗号化するため、ファイルアクセスを許可してください。",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        context.startActivity(intent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlueBright),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("許可する", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = ElectricBlueBright,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else if (displayedSecretVideos.isEmpty()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EnhancedEncryption,
                                    contentDescription = null,
                                    tint = ElectricBlue.copy(alpha = 0.4f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "シークレット動画はまだありません",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "メイン画面で動画を長押し選択して移動できます",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            AnimatedContent(
                                targetState = isGridView,
                                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(180)) },
                                label = "SecretViewMode"
                            ) { gridMode ->
                                if (gridMode) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            start = 10.dp,
                                            end = 10.dp,
                                            top = 10.dp,
                                            bottom = 140.dp
                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(displayedSecretVideos, key = { it.id }) { video ->
                                            val isSelected = selectedIds.contains(video.id)
                                            VideoGridItemCard(
                                                video = video,
                                                isSelectionMode = isSelectionMode,
                                                isSelected = isSelected,
                                                isCurrentlyPlaying = currentlyPlayingVideoId == video.id,
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        selectedIds = if (isSelected) selectedIds - video.id else selectedIds + video.id
                                                    } else {
                                                        onPlayVideo(video)
                                                    }
                                                },
                                                onLongClick = {
                                                    selectedIds = selectedIds + video.id
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
                                        contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp)
                                    ) {
                                        items(displayedSecretVideos, key = { it.id }) { video ->
                                            val isSelected = selectedIds.contains(video.id)
                                            VideoItemCard(
                                                video = video,
                                                isSelectionMode = isSelectionMode,
                                                isSelected = isSelected,
                                                isCurrentlyPlaying = currentlyPlayingVideoId == video.id,
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        selectedIds = if (isSelected) selectedIds - video.id else selectedIds + video.id
                                                    } else {
                                                        onPlayVideo(video)
                                                    }
                                                },
                                                onLongClick = {
                                                    selectedIds = selectedIds + video.id
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
                    }
                }

                // 下部一括操作バー（復号して一覧へ戻す / 完全削除）
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(20.dp, RoundedCornerShape(20.dp), spotColor = ElectricBlue)
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceElevated)
                            .border(1.dp, ElectricBlueBright.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val targets = secretVideos.filter { selectedIds.contains(it.id) }
                                selectedIds = emptySet()
                                coroutineScope.launch {
                                    val count = vaultManager.restoreFromVault(targets)
                                    Toast.makeText(context, "${count}件を復号して通常一覧へ戻しました", Toast.LENGTH_SHORT).show()
                                    refreshSecretVideos()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlueBright),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("一覧へ戻す (${selectedIds.size})", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Button(
                            onClick = {
                                val targets = secretVideos.filter { selectedIds.contains(it.id) }
                                selectedIds = emptySet()
                                coroutineScope.launch {
                                    val count = vaultManager.deleteFromVault(targets)
                                    Toast.makeText(context, "${count}件を削除しました", Toast.LENGTH_SHORT).show()
                                    refreshSecretVideos()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DangerRed.copy(alpha = 0.2f),
                                contentColor = DangerRed
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .border(1.dp, DangerRed.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("削除", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
