package com.kooric.krcplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kooric.krcplayer.ui.screens.FolderVideosScreen
import com.kooric.krcplayer.ui.screens.HomeScreen
import com.kooric.krcplayer.ui.screens.PlayerScreen
import com.kooric.krcplayer.ui.screens.SecretVaultScreen
import com.kooric.krcplayer.ui.theme.KrcPlayerTheme
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
                    KrcAppNavigation()
                } else {
                    // 権限リクエスト表示
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "動画を再生・管理するために\nストレージへのアクセス許可が必要です。",
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { permissionLauncher.launch(permissionToRequest) }) {
                                Text("権限を許可する")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KrcAppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onPlayVideo = { uriString ->
                    val encoded = URLEncoder.encode(uriString, StandardCharsets.UTF_8.toString())
                    navController.navigate("player/$encoded")
                },
                onOpenFolder = { folderName ->
                    val encoded = URLEncoder.encode(folderName, StandardCharsets.UTF_8.toString())
                    navController.navigate("folder/$encoded")
                },
                onOpenSecretVault = {
                    navController.navigate("secret")
                }
            )
        }

        composable(
            route = "folder/{folderName}",
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("folderName") ?: ""
            val folderName = URLDecoder.decode(raw, StandardCharsets.UTF_8.toString())
            FolderVideosScreen(
                folderName = folderName,
                onBack = { navController.popBackStack() },
                onPlayVideo = { uriString ->
                    val encoded = URLEncoder.encode(uriString, StandardCharsets.UTF_8.toString())
                    navController.navigate("player/$encoded")
                }
            )
        }

        composable(
            route = "player/{videoUri}",
            arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("videoUri") ?: ""
            val videoUri = URLDecoder.decode(raw, StandardCharsets.UTF_8.toString())
            PlayerScreen(
                videoUriString = videoUri,
                onBack = { navController.popBackStack() }
            )
        }

        composable("secret") {
            SecretVaultScreen(
                onBack = { navController.popBackStack() },
                onPlayVideo = { uriString ->
                    val encoded = URLEncoder.encode(uriString, StandardCharsets.UTF_8.toString())
                    navController.navigate("player/$encoded")
                }
            )
        }
    }
}
