package com.kooric.krcplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kooric.krcplayer.data.SecretVaultManager
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.components.VideoItemCard
import com.kooric.krcplayer.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretVaultScreen(
    onBack: () -> Unit,
    onPlayVideo: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vaultManager = remember { SecretVaultManager(context) }

    var isUnlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    val isPinAlreadySet = remember { vaultManager.isPinSet() }
    var secretVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    fun refreshSecretVideos() {
        coroutineScope.launch {
            secretVideos = vaultManager.getSecretVideos()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("シークレットフォルダ", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!isUnlocked) {
                // PIN入力 / 設定画面
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isPinAlreadySet) "PINコードを入力してください" else "4桁のPINコードを設定してください",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 4) pinInput = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        placeholder = { Text("PIN (4桁)") }
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
                                Toast.makeText(context, "PINを設定しました", Toast.LENGTH_SHORT).show()
                                isUnlocked = true
                                refreshSecretVideos()
                            } else {
                                if (vaultManager.verifyPin(pinInput)) {
                                    isUnlocked = true
                                    refreshSecretVideos()
                                } else {
                                    Toast.makeText(context, "PINコードが違います", Toast.LENGTH_SHORT).show()
                                    pinInput = ""
                                }
                            }
                        },
                        modifier = Modifier.width(160.dp)
                    ) {
                        Text(if (isPinAlreadySet) "ロック解除" else "設定して開く")
                    }
                }
            } else {
                // ロック解除後の動画リスト
                if (secretVideos.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "シークレット動画はありません。\n動画リストのメニューから移動できます。",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(secretVideos) { video ->
                            VideoItemCard(
                                video = video,
                                onClick = { onPlayVideo(video.uri.toString()) }
                            )
                        }
                    }
                }
            }
        }
    }
}
