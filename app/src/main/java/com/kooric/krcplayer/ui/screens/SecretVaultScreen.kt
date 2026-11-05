package com.kooric.krcplayer.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kooric.krcplayer.data.SecretVaultManager
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.components.VideoItemCard
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
                // PINコード入力カード
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(24.dp))
                        .background(SurfaceDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .background(DeepBlueSurface, CircleShape)
                            .border(1.5.dp, ElectricBlueBright, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock",
                            modifier = Modifier.size(32.dp),
                            tint = ElectricBlueGlow
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = if (isPinAlreadySet) "シークレットロック解除" else "新規PINコード設定",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (isPinAlreadySet) "4桁のPINコードを入力してください" else "保護用の4桁のPINコードを決めてください",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 4) pinInput = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        placeholder = { Text("••••") },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlueBright,
                            unfocusedBorderColor = CardBorder,
                            focusedContainerColor = SurfaceElevated,
                            unfocusedContainerColor = SurfaceElevated
                        ),
                        modifier = Modifier.width(180.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            if (pinInput.length < 4) {
                                Toast.makeText(context, "4桁のPINを入力してください", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            if (!isPinAlreadySet) {
                                vaultManager.setPin(pinInput)
                                Toast.makeText(context, "PINコードを設定しました", Toast.LENGTH_SHORT).show()
                                isUnlocked = true
                                refreshSecretVideos()
                            } else {
                                if (vaultManager.verifyPin(pinInput)) {
                                    isUnlocked = true
                                    refreshSecretVideos()
                                } else {
                                    Toast.makeText(context, "PINコードが正しくありません", Toast.LENGTH_SHORT).show()
                                    pinInput = ""
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlueBright),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = if (isPinAlreadySet) "ロック解除" else "設定して開く",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }
                }
            } else {
                // ロック解除後の一覧
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp)
                    ) {
                        items(secretVideos, key = { it.path }) { video ->
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
                                }
                            )
                        }
                    }
                }

                // シークレット内での一括操作バー（復元 / 完全削除）
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
                                coroutineScope.launch {
                                    val count = vaultManager.restoreFromVault(targets)
                                    Toast.makeText(context, "${count}件を通常一覧へ戻しました", Toast.LENGTH_SHORT).show()
                                    selectedIds = emptySet()
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
                                coroutineScope.launch {
                                    val count = vaultManager.deleteFromVault(targets)
                                    Toast.makeText(context, "${count}件を削除しました", Toast.LENGTH_SHORT).show()
                                    selectedIds = emptySet()
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
