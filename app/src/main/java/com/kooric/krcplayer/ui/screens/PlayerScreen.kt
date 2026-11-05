package com.kooric.krcplayer.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun OverlayVideoPlayer(
    video: VideoItem,
    isMinimized: Boolean,
    onMinimizeChange: (Boolean) -> Unit,
    onClosePlayer: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // アプリ個別音量 (0.0f ~ 2.0f : 0% ~ 200%)
    var appVolume by remember { mutableFloatStateOf(1.0f) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // 音量をExoPlayerとLoudnessEnhancerに反映する関数
    fun applyAppVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 2.0f)
        appVolume = clamped
        exoPlayer.volume = clamped.coerceAtMost(1.0f)

        try {
            if (clamped > 1.0f) {
                if (loudnessEnhancer == null && exoPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    loudnessEnhancer = LoudnessEnhancer(exoPlayer.audioSessionId).apply {
                        enabled = true
                    }
                }
                // 1.0 ~ 2.0 を 0mB ~ 1500mB にマッピング
                val gainMb = ((clamped - 1.0f) * 1500).toInt()
                loudnessEnhancer?.setTargetGain(gainMb)
                loudnessEnhancer?.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
            }
        } catch (_: Exception) {
        }
    }

    // 動画切り替え時
    LaunchedEffect(video.uri) {
        val mediaItem = MediaItem.fromUri(video.uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        applyAppVolume(appVolume)
    }

    // ミニスクリーン化時は画面向き固定を解除
    LaunchedEffect(isMinimized) {
        if (isMinimized) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                loudnessEnhancer?.release()
            } catch (_: Exception) {
            }
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // フルスクリーン時にスマホの戻るボタンを押したらミニスクリーン化
    BackHandler(enabled = !isMinimized) {
        onMinimizeChange(true)
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showVolumeSheet by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // ジェスチャー表示用
    var gestureOverlayTitle by remember { mutableStateOf<String?>(null) }
    var gestureOverlayValue by remember { mutableStateOf<String?>(null) }
    var gestureProgress by remember { mutableFloatStateOf(-1f) }

    // ミニプレイヤーのドラッグ移動オフセット
    var miniOffsetX by remember { mutableFloatStateOf(0f) }
    var miniOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(exoPlayer) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        })

        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            totalDuration = exoPlayer.duration.coerceAtLeast(0L)
            delay(300)
        }
    }

    LaunchedEffect(isControlsVisible, isMinimized) {
        if (isControlsVisible && !isScreenLocked && !isMinimized) {
            delay(3800)
            isControlsVisible = false
        }
    }

    val cornerRadius by animateDpAsState(
        targetValue = if (isMinimized) 16.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "PlayerCorner"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        // フルスクリーン ⇔ 右下ミニスクリーンのコンテナ
        val containerModifier = if (isMinimized) {
            Modifier
                .padding(end = 16.dp, bottom = 24.dp)
                .offset { IntOffset(miniOffsetX.roundToInt(), miniOffsetY.roundToInt()) }
                .width(230.dp)
                .height(152.dp)
                .shadow(20.dp, RoundedCornerShape(cornerRadius), spotColor = ElectricBlue)
                .clip(RoundedCornerShape(cornerRadius))
                .background(SurfaceDark)
                .border(1.5.dp, ElectricBlueBright.copy(alpha = 0.85f), RoundedCornerShape(cornerRadius))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        miniOffsetX += dragAmount.x
                        miniOffsetY += dragAmount.y
                    }
                }
        } else {
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(isScreenLocked) {
                    detectTapGestures(
                        onTap = { isControlsVisible = !isControlsVisible },
                        onDoubleTap = { offset ->
                            if (!isScreenLocked) {
                                val isLeft = offset.x < size.width / 2
                                val target = if (isLeft) {
                                    (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                } else {
                                    (exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration)
                                }
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            }
                        }
                    )
                }
                .pointerInput(isScreenLocked) {
                    if (isScreenLocked) return@pointerInput

                    var startX = 0f
                    var isHorizontal = false
                    var isVertical = false

                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            isHorizontal = false
                            isVertical = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val isLeftSide = startX < size.width / 2

                            if (!isHorizontal && !isVertical) {
                                if (abs(dragAmount.x) > abs(dragAmount.y) && abs(dragAmount.x) > 8) {
                                    isHorizontal = true
                                } else if (abs(dragAmount.y) > abs(dragAmount.x) && abs(dragAmount.y) > 8) {
                                    isVertical = true
                                }
                            }

                            if (isHorizontal) {
                                val seekDelta = (dragAmount.x * 160).toLong()
                                val newPos = (exoPlayer.currentPosition + seekDelta).coerceIn(0L, exoPlayer.duration.coerceAtLeast(1L))
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                                gestureOverlayTitle = if (dragAmount.x >= 0) "早送り >>" else "<< 巻き戻し"
                                gestureOverlayValue = formatTime(newPos)
                                gestureProgress = if (totalDuration > 0) newPos.toFloat() / totalDuration else -1f
                            } else if (isVertical) {
                                val deltaRatio = -dragAmount.y / size.height.toFloat()
                                if (isLeftSide) {
                                    activity?.let { act ->
                                        val lp = act.window.attributes
                                        val currentB = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                        val newB = (currentB + deltaRatio * 1.4f).coerceIn(0.01f, 1.0f)
                                        lp.screenBrightness = newB
                                        act.window.attributes = lp
                                        gestureOverlayTitle = "画面の明るさ"
                                        gestureOverlayValue = "${(newB * 100).toInt()}%"
                                        gestureProgress = newB
                                    }
                                } else {
                                    // アプリ独自の個別音量 (0% ~ 200%)
                                    val newVol = (appVolume + deltaRatio * 1.8f).coerceIn(0f, 2.0f)
                                    applyAppVolume(newVol)
                                    val percent = (newVol * 100).toInt()
                                    gestureOverlayTitle = if (percent > 100) "アプリ音量 (ブースト)" else "アプリ個別音量"
                                    gestureOverlayValue = "$percent%"
                                    gestureProgress = (newVol / 2.0f).coerceIn(0f, 1f)
                                }
                            }
                        },
                        onDragEnd = {
                            gestureOverlayTitle = null
                            gestureOverlayValue = null
                            gestureProgress = -1f
                        }
                    )
                }
        }

        Box(modifier = containerModifier) {
            if (isMinimized) {
                // ===== 右下ミニスクリーン表示 =====
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black)
                            .clickable {
                                // タップでフルスクリーンに戻る
                                miniOffsetX = 0f
                                miniOffsetY = 0f
                                onMinimizeChange(false)
                            }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { view ->
                                view.player = exoPlayer
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // 左上にフルスクリーン復帰アイコン
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .size(26.dp)
                                .background(PureBlack.copy(alpha = 0.7f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Expand",
                                tint = ElectricBlueGlow,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // ミニプレイヤー下部コントロールバー
                    LinearProgressIndicator(
                        progress = { if (totalDuration > 0) (currentPosition.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = ElectricBlueBright,
                        trackColor = SurfaceElevated
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .background(SurfaceDark)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = video.title,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = ElectricBlueGlow,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onClosePlayer,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            } else {
                // ===== フルスクリーン表示 =====
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            this.resizeMode = resizeMode
                        }
                    },
                    update = { view ->
                        view.player = exoPlayer
                        view.resizeMode = resizeMode
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ジェスチャーオーバーレイ (音量・輝度・シーク)
                AnimatedVisibility(
                    visible = gestureOverlayTitle != null,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Column(
                        modifier = Modifier
                            .width(180.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(PureBlack.copy(alpha = 0.85f))
                            .border(1.dp, ElectricBlueBright.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = gestureOverlayTitle ?: "",
                            color = ElectricBlueGlow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = gestureOverlayValue ?: "",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (gestureProgress >= 0f) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { gestureProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = ElectricBlueBright,
                                trackColor = SurfaceElevated
                            )
                        }
                    }
                }

                // フルスクリーンコントロールUI
                AnimatedVisibility(
                    visible = isControlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.75f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.85f)
                                    )
                                )
                            )
                    ) {
                        // 上部バー：「←」を押すと右下ミニスクリーン化して一覧へ戻る！
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onMinimizeChange(true) },
                                modifier = Modifier
                                    .background(SurfaceDark.copy(alpha = 0.7f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "ミニスクリーン化して戻る",
                                    tint = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = video.title,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "← で右下ミニプレイヤーへ縮小",
                                    color = ElectricBlueGlow,
                                    fontSize = 11.sp
                                )
                            }

                            // 個別音量ボタン
                            Surface(
                                onClick = { showVolumeSheet = true },
                                color = DeepBlueSurface.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Volume",
                                        tint = ElectricBlueGlow,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${(appVolume * 100).toInt()}%",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // 倍速ボタン
                            Surface(
                                onClick = { showSpeedDialog = true },
                                color = SurfaceElevated.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = "${playbackSpeed}x",
                                    color = ElectricBlueGlow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            // アスペクト比切り替え
                            IconButton(onClick = {
                                resizeMode = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            }) {
                                Icon(Icons.Default.AspectRatio, contentDescription = "AspectRatio", tint = Color.White)
                            }

                            // システムPiP (ホーム画面に戻る小窓)
                            IconButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val params = PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .build()
                                    activity?.enterPictureInPictureMode(params)
                                }
                            }) {
                                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "System PiP", tint = Color.White)
                            }

                            // 画面回転
                            IconButton(onClick = {
                                activity?.let { act ->
                                    act.requestedOrientation = if (act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    }
                                }
                            }) {
                                Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
                            }
                        }

                        // 画面ロックボタン
                        IconButton(
                            onClick = { isScreenLocked = !isScreenLocked },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 20.dp)
                                .size(48.dp)
                                .background(
                                    if (isScreenLocked) ElectricBlue else SurfaceDark.copy(alpha = 0.75f),
                                    CircleShape
                                )
                                .border(1.dp, ElectricBlueBright.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isScreenLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Lock",
                                tint = Color.White
                            )
                        }

                        if (!isScreenLocked) {
                            // 中央コントロール（10秒戻し・再生/一時停止・10秒送り）
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                        exoPlayer.seekTo(target)
                                        currentPosition = target
                                    },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(SurfaceDark.copy(alpha = 0.65f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Replay10,
                                        contentDescription = "-10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    },
                                    modifier = Modifier
                                        .size(72.dp)
                                        .shadow(16.dp, CircleShape, spotColor = ElectricBlue)
                                        .background(ElectricBlueBright, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration)
                                        exoPlayer.seekTo(target)
                                        currentPosition = target
                                    },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(SurfaceDark.copy(alpha = 0.65f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Forward10,
                                        contentDescription = "+10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            // 下部シークバー＆時間
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatTime(currentPosition),
                                        color = ElectricBlueGlow,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = formatTime(totalDuration),
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Slider(
                                    value = if (totalDuration > 0) (currentPosition.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f,
                                    onValueChange = { ratio ->
                                        val target = (ratio * totalDuration).toLong()
                                        exoPlayer.seekTo(target)
                                        currentPosition = target
                                    },
                                    colors = SliderDefaults.colors(
                                        thumbColor = ElectricBlueGlow,
                                        activeTrackColor = ElectricBlueBright,
                                        inactiveTrackColor = SurfaceElevated
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // アプリ個別音量調整ダイアログ (0% ~ 200%)
    if (showVolumeSheet) {
        AlertDialog(
            onDismissRequest = { showVolumeSheet = false },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            title = {
                Text("KrcPlayer 個別音量設定", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text(
                        text = "スマホ本体のメディア音量とは別に、このプレイヤーだけの音量を 0% 〜 200% で個別調整できます（画面右側の縦スワイプでも調整可能）。",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "現在の音量: ${(appVolume * 100).toInt()}%",
                        color = ElectricBlueGlow,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = appVolume,
                        onValueChange = { applyAppVolume(it) },
                        valueRange = 0f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricBlueGlow,
                            activeTrackColor = ElectricBlueBright,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { applyAppVolume(0f) }) { Text("0% (ミュート)") }
                        TextButton(onClick = { applyAppVolume(1.0f) }) { Text("100% (標準)") }
                        TextButton(onClick = { applyAppVolume(2.0f) }) { Text("200% (最大)") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showVolumeSheet = false }) {
                    Text("完了")
                }
            }
        )
    }

    // 倍速選択ダイアログ
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            title = { Text("再生速度", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    speeds.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    playbackSpeed = speed
                                    exoPlayer.playbackParameters = PlaybackParameters(speed)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = playbackSpeed == speed,
                                onClick = {
                                    playbackSpeed = speed
                                    exoPlayer.playbackParameters = PlaybackParameters(speed)
                                    showSpeedDialog = false
                                }
                            )
                            Text(
                                text = "${speed}x",
                                color = TextPrimary,
                                fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
