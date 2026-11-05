package com.kooric.krcplayer.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kooric.krcplayer.data.SecretVaultManager
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.components.VideoGridItemCard
import com.kooric.krcplayer.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretVaultScreen(
    currentlyPlayingVideoId: Long?,
    onBack: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vaultManager = remember { SecretVaultManager(context) }

    var isUnlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    val isPinAlreadySet = remember { vaultManager.isPinSet() }
    var secretVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val isSelectionMode = selectedIds.isNotEmpty()

    fun refreshSecretVideos() {
        coroutineScope.launch {
            secretVideos = vaultManager.getSecretVideos()
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

    Scaffold(
        containerColor = PureBlack,
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(
                            text = "${selectedIds.size} 件選択中",
                            color = ElectricBlueGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    } else {
                        Text(
                            text = "シークレットフォルダ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSelectionMode) {
                                selectedIds = emptySet()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (isUnlocked && secretVideos.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedIds = if (selectedIds.size == secretVideos.size) {
                                    emptySet()
                                } else {
                                    secretVideos.map { it.id }.toSet()
                                }
                            }
                        ) {
                            Text(
                                text = if (selectedIds.size == secretVideos.size) "全解除" else "すべて選択",
                                color = ElectricBlueGlow,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PureBlack
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(PureBlack)
        ) {
            if (!isUnlocked) {
                // ===== 4桁入力した瞬間に自動で開く高速PINパッドUI =====
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
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock",
                            modifier = Modifier.size(30.dp),
                            tint = ElectricBlueGlow
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isPinAlreadySet) "4桁のPINを入力（自動解除）" else "新規4桁PINコードを設定",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 4つのドットインジケーター
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

                    // テンキーパッド (1〜9, 0, バックスペース)
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("", "0", "DEL")
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
                                        "" -> Spacer(modifier = Modifier.size(72.dp))
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
                // ロック解除後の一覧（横3列グリッド）
                if (secretVideos.isEmpty()) {
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
                        items(secretVideos, key = { it.id }) { video ->
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
                                }
                            )
                        }
                    }
                }

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
                                    Toast.makeText(context, "${count}件を通常一覧へ戻しました", Toast.LENGTH_SHORT).show()
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
