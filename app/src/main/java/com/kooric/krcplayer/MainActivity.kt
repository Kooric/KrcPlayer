package com.kooric.krcplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kooric.krcplayer.data.RecentHistoryManager
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.screens.HomeScreen
import com.kooric.krcplayer.ui.screens.OverlayVideoPlayer
import com.kooric.krcplayer.ui.screens.SecretVaultScreen
import com.kooric.krcplayer.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KrcPlayerTheme {
                val context = LocalContext.current
                val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                var hasPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            permissionToRequest
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasPermission = isGranted
                }

                LaunchedEffect(Unit) {
                    if (!hasPermission) {
                        permissionLauncher.launch(permissionToRequest)
                    }
                }

                if (hasPermission) {
                    KrcAppRoot()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PureBlack)
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(SurfaceDark)
                                .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(DeepBlueSurface, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideoLibrary,
                                    contentDescription = null,
                                    tint = ElectricBlueGlow,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "動画アクセス権限が必要です",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "端末内の動画をスキャン・再生するためにストレージへのアクセスを許可してください。",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { permissionLauncher.launch(permissionToRequest) },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlueBright),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("権限を許可する", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KrcAppRoot() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val historyManager = remember { RecentHistoryManager(context) }

    var activeVideo by remember { mutableStateOf<VideoItem?>(null) }
    var isPlayerMinimized by remember { mutableStateOf(false) }
    var recentRefreshTick by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(PureBlack)) {
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(260)
                ) + fadeIn(tween(260))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(260)
                ) + fadeOut(tween(200))
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(260)
                ) + fadeIn(tween(260))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(260)
                ) + fadeOut(tween(200))
            }
        ) {
            composable("home") {
                HomeScreen(
                    currentlyPlayingVideoId = activeVideo?.id,
                    recentRefreshTick = recentRefreshTick,
                    onPlayVideo = { video ->
                        historyManager.recordPlayedVideo(video.id)
                        recentRefreshTick++
                        activeVideo = video
                        isPlayerMinimized = false
                    },
                    onOpenSecretVault = {
                        navController.navigate("secret")
                    }
                )
            }

            composable("secret") {
                SecretVaultScreen(
                    currentlyPlayingVideoId = activeVideo?.id,
                    onBack = { navController.popBackStack() },
                    onPlayVideo = { video ->
                        activeVideo = video
                        isPlayerMinimized = false
                    }
                )
            }
        }

        // 上層オーバーレイプレイヤー
        activeVideo?.let { currentVideo ->
            OverlayVideoPlayer(
                video = currentVideo,
                isMinimized = isPlayerMinimized,
                onMinimizeChange = { minimized ->
                    isPlayerMinimized = minimized
                },
                onClosePlayer = {
                    activeVideo = null
                    isPlayerMinimized = false
                }
            )
        }
    }
}
