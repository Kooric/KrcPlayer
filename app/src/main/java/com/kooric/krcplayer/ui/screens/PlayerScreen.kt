package com.kooric.krcplayer.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()

    // おやすみタイマー設定の永続保存用 SharedPreferences
    val timerPrefs = remember {
        context.getSharedPreferences("krc_sleep_timer_prefs", Context.MODE_PRIVATE)
    }
    var savedTimerHours by remember { mutableIntStateOf(timerPrefs.getInt("saved_hours", 0)) }
    var savedTimerMinutes by remember { mutableIntStateOf(timerPrefs.getInt("saved_minutes", 30)) }

    // 現在作動中のおやすみタイマー残り秒数 (0以下なら未作動)
    var sleepTimerRemainingSeconds by remember { mutableLongStateOf(0L) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    // アプリ個別音量 (0.0f ~ 3.0f : 0% ~ 300% ブースト対応)
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

    fun applyAppVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 3.0f)
        appVolume = clamped
        exoPlayer.volume = clamped.coerceAtMost(1.0f)

        try {
            if (clamped > 1.0f) {
                if (loudnessEnhancer == null && exoPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    loudnessEnhancer = LoudnessEnhancer(exoPlayer.audioSessionId).apply {
                        enabled = true
                    }
                }
                val gainMb = (((clamped - 1.0f) / 2.0f) * 2500).toInt()
                loudnessEnhancer?.setTargetGain(gainMb)
                loudnessEnhancer?.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
            }
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(video.uri) {
        val mediaItem = MediaItem.fromUri(video.uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        applyAppVolume(appVolume)
    }

    LaunchedEffect(isMinimized) {
        if (isMinimized) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // おやすみタイマーのカウントダウン処理
    LaunchedEffect(sleepTimerRemainingSeconds > 0L) {
        while (sleepTimerRemainingSeconds > 0L) {
            delay(1000L)
            sleepTimerRemainingSeconds -= 1L
            if (sleepTimerRemainingSeconds == 0L) {
                exoPlayer.pause()
                Toast.makeText(context, "🌙 おやすみタイマーにより再生を停止しました", Toast.LENGTH_LONG).show()
            }
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

    // スワイプジェスチャー表示用
    var gestureOverlayTitle by remember { mutableStateOf<String?>(null) }
    var gestureOverlayValue by remember { mutableStateOf<String?>(null) }
    var gestureProgress by remember { mutableFloatStateOf(-1f) }

    // ダブルタップ10秒シークのアニメーション表示用 (-1: 左10秒戻し, 1: 右10秒送り, 0: 非表示)
    var doubleTapSeekSide by remember { mutableIntStateOf(0) }
    var doubleTapSeekAccumulated by remember { mutableIntStateOf(0) }
    var doubleTapTriggerTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(doubleTapTriggerTick) {
        if (doubleTapSeekSide != 0) {
            delay(750L)
            doubleTapSeekSide = 0
            doubleTapSeekAccumulated = 0
        }
    }

    // ミニプレイヤーのドラッグ移動オフセット
    var miniOffsetX by remember { mutableFloatStateOf(0f) }
    var miniOffsetY by remember { mutableFloatStateOf(0f) }

    // フルスクリーン展開時はオフセットを滑らかに0へ戻す
    val animOffsetX by animateFloatAsState(
        targetValue = if (isMinimized) miniOffsetX else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "MiniOffsetX"
    )
    val animOffsetY by animateFloatAsState(
        targetValue = if (isMinimized) miniOffsetY else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "MiniOffsetY"
    )

    LaunchedEffect(exoPlayer) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                    applyAppVolume(appVolume)
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

    // ===== BoxWithConstraints でフルスクリーン ⇔ 右下ミニプレイヤーのサイズ・位置・角丸を連続アニメーション補間 =====
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        val targetWidth = if (isMinimized) 232.dp else maxWidth
        val targetHeight = if (isMinimized) 154.dp else maxHeight
        val targetPadEnd = if (isMinimized) 16.dp else 0.dp
        val targetPadBottom = if (isMinimized) 24.dp else 0.dp
        val targetCorner = if (isMinimized) 16.dp else 0.dp
        val targetBorderWidth = if (isMinimized) 1.5.dp else 0.dp

        val smoothSpring = spring<androidx.compose.ui.unit.Dp>(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        )

        val animWidth by animateDpAsState(targetValue = targetWidth, animationSpec = smoothSpring, label = "PlayerWidth")
        val animHeight by animateDpAsState(targetValue = targetHeight, animationSpec = smoothSpring, label = "PlayerHeight")
        val animPadEnd by animateDpAsState(targetValue = targetPadEnd, animationSpec = smoothSpring, label = "PlayerPadEnd")
        val animPadBottom by animateDpAsState(targetValue = targetPadBottom, animationSpec = smoothSpring, label = "PlayerPadBottom")
        val animCorner by animateDpAsState(targetValue = targetCorner, animationSpec = smoothSpring, label = "PlayerCorner")
        val animBorder by animateDpAsState(targetValue = targetBorderWidth, animationSpec = smoothSpring, label = "PlayerBorder")

        Box(
            modifier = Modifier
                .padding(end = animPadEnd, bottom = animPadBottom)
                .offset { IntOffset(animOffsetX.roundToInt(), animOffsetY.roundToInt()) }
                .width(animWidth)
                .height(animHeight)
                .shadow(
                    elevation = if (isMinimized) 22.dp else 0.dp,
                    shape = RoundedCornerShape(animCorner),
                    spotColor = ElectricBlue
                )
                .clip(RoundedCornerShape(animCorner))
                .background(Color.Black)
                .border(
                    width = animBorder,
                    color = if (isMinimized) ElectricBlueBright.copy(alpha = 0.85f) else Color.Transparent,
                    shape = RoundedCornerShape(animCorner)
                )
        ) {
            // ===== 共通の Video Surface (拡大縮小中も破棄されず滑らかにリサイズ) =====
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                        // ミニスクリーン時はドラッグ移動＆タップでフルスクリーン展開
                        .then(
                            if (isMinimized) {
                                Modifier
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = {
                                                miniOffsetX = 0f
                                                miniOffsetY = 0f
                                                onMinimizeChange(false)
                                            }
                                        )
                                    }
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            miniOffsetX += dragAmount.x
                                            miniOffsetY += dragAmount.y
                                        }
                                    }
                            } else {
                                // フルスクリーン時はシングルタップでUI表示切替、左右ダブルタップで10秒戻る/進む、ドラッグで音量・輝度・シーク
                                Modifier
                                    .pointerInput(isScreenLocked) {
                                        detectTapGestures(
                                            onTap = {
                                                isControlsVisible = !isControlsVisible
                                            },
                                            onDoubleTap = { offset ->
                                                if (!isScreenLocked) {
                                                    val isLeft = offset.x < size.width / 2
                                                    if (isLeft) {
                                                        val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                                        exoPlayer.seekTo(target)
                                                        currentPosition = target
                                                        doubleTapSeekSide = -1
                                                        doubleTapSeekAccumulated = if (doubleTapSeekSide == -1) doubleTapSeekAccumulated + 10 else 10
                                                        doubleTapTriggerTick++
                                                    } else {
                                                        val durationLimit = exoPlayer.duration.coerceAtLeast(0L)
                                                        val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(durationLimit)
                                                        exoPlayer.seekTo(target)
                                                        currentPosition = target
                                                        doubleTapSeekSide = 1
                                                        doubleTapSeekAccumulated = if (doubleTapSeekSide == 1) doubleTapSeekAccumulated + 10 else 10
                                                        doubleTapTriggerTick++
                                                    }
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
                                                        val newVol = (appVolume + deltaRatio * 2.4f).coerceIn(0f, 3.0f)
                                                        applyAppVolume(newVol)
                                                        val percent = (newVol * 100).toInt()
                                                        gestureOverlayTitle = if (percent > 100) "音量ブースト" else "アプリ個別音量"
                                                        gestureOverlayValue = "$percent%"
                                                        gestureProgress = (newVol / 3.0f).coerceIn(0f, 1f)
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
                        )
                ) {
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
                            view.resizeMode = if (isMinimized) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // ===== 縮小時のミニウィンドウ用オーバーレイ（動画の右上に×ボタンを配置！） =====
                    this@Column.AnimatedVisibility(
                        visible = isMinimized,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(150)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 左上：フルスクリーン展開アイコン
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(PureBlack.copy(alpha = 0.72f))
                                    .border(1.dp, ElectricBlueGlow.copy(alpha = 0.5f), CircleShape)
                                    .clickable {
                                        miniOffsetX = 0f
                                        miniOffsetY = 0f
                                        onMinimizeChange(false)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInFull,
                                    contentDescription = "Expand",
                                    tint = ElectricBlueGlow,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            // 右上：動画の上に載せる「×（閉じる）」ボタン！
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(PureBlack.copy(alpha = 0.78f))
                                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                    .clickable { onClosePlayer() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // ===== ダブルタップ 10秒戻る / 10秒進む の波紋インジケーター =====
                    this@Column.AnimatedVisibility(
                        visible = !isMinimized && doubleTapSeekSide == -1,
                        enter = fadeIn(tween(150)) + scaleIn(spring(stiffness = Spring.StiffnessMedium)),
                        exit = fadeOut(tween(250)),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 48.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ElectricBlue.copy(alpha = 0.35f))
                                .border(1.dp, ElectricBlueGlow, RoundedCornerShape(50))
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = "-${doubleTapSeekAccumulated}秒",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    this@Column.AnimatedVisibility(
                        visible = !isMinimized && doubleTapSeekSide == 1,
                        enter = fadeIn(tween(150)) + scaleIn(spring(stiffness = Spring.StiffnessMedium)),
                        exit = fadeOut(tween(250)),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 48.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ElectricBlue.copy(alpha = 0.35f))
                                .border(1.dp, ElectricBlueGlow, RoundedCornerShape(50))
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = "+${doubleTapSeekAccumulated}秒",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // ===== スワイプジェスチャーオーバーレイ (音量・輝度・シーク) =====
                    this@Column.AnimatedVisibility(
                        visible = !isMinimized && gestureOverlayTitle != null,
                        enter = fadeIn(tween(150)) + scaleIn(),
                        exit = fadeOut(tween(200)) + scaleOut(),
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

                    // ===== フルスクリーンコントロールUI =====
                    this@Column.AnimatedVisibility(
                        visible = !isMinimized && isControlsVisible,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(220)),
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
                            // 上部バー
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onMinimizeChange(true) },
                                    modifier = Modifier
                                        .background(SurfaceDark.copy(alpha = 0.7f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Minimize",
                                        tint = Color.White
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Text(
                                    text = video.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                // 🌙 おやすみタイマーボタン
                                val timerActive = sleepTimerRemainingSeconds > 0L
                                val pillShape = RoundedCornerShape(50)
                                Row(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(pillShape)
                                        .background(if (timerActive) ElectricBlue else SurfaceElevated.copy(alpha = 0.85f))
                                        .border(
                                            1.dp,
                                            if (timerActive) ElectricBlueGlow else CardBorder,
                                            pillShape
                                        )
                                        .clickable { showSleepTimerDialog = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bedtime,
                                        contentDescription = "おやすみタイマー",
                                        tint = if (timerActive) Color.White else ElectricBlueGlow,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    if (timerActive) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = formatTime(sleepTimerRemainingSeconds * 1000L),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // 音量ブーストボタン (0% ~ 300%)
                                Row(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(pillShape)
                                        .background(DeepBlueSurface.copy(alpha = 0.85f))
                                        .border(1.dp, ElectricBlueBright.copy(alpha = 0.6f), pillShape)
                                        .clickable { showVolumeSheet = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Volume",
                                        tint = ElectricBlueGlow,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${(appVolume * 100).toInt()}%",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // 倍速ボタン
                                Box(
                                    modifier = Modifier
                                        .clip(pillShape)
                                        .background(SurfaceElevated.copy(alpha = 0.85f))
                                        .border(1.dp, CardBorder, pillShape)
                                        .clickable { showSpeedDialog = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = ElectricBlueGlow,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }

                                // アスペクト比
                                IconButton(onClick = {
                                    resizeMode = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                }) {
                                    Icon(Icons.Default.AspectRatio, contentDescription = "AspectRatio", tint = Color.White)
                                }

                                // システムPiP
                                IconButton(onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        val params = PictureInPictureParams.Builder()
                                            .setAspectRatio(Rational(16, 9))
                                            .build()
                                        activity?.enterPictureInPictureMode(params)
                                    }
                                }) {
                                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
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
                                            val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
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

                                // 下部シークバー
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

                // ===== ミニウィンドウ下部バー（タイトル ＆ 再生/一時停止ボタン、×は動画右上に移動済み） =====
                AnimatedVisibility(
                    visible = isMinimized,
                    enter = fadeIn(tween(200)) + expandVertically(),
                    exit = fadeOut(tween(150)) + shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                                .height(36.dp)
                                .background(SurfaceDark)
                                .clickable {
                                    miniOffsetX = 0f
                                    miniOffsetY = 0f
                                    onMinimizeChange(false)
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = video.title,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
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
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ===== 🌙 おやすみタイマー設定ダイアログ（次回も使えるように設定時間を自動保存） =====
    if (showSleepTimerDialog) {
        var inputHours by remember { mutableIntStateOf(savedTimerHours) }
        var inputMinutes by remember { mutableIntStateOf(savedTimerMinutes) }

        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = ElectricBlueGlow)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("おやすみタイマー", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (sleepTimerRemainingSeconds > 0L) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DeepBlueSurface)
                                .border(1.dp, ElectricBlueBright, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "作動中 • 残り ${formatTime(sleepTimerRemainingSeconds * 1000L)}",
                                color = ElectricBlueGlow,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    Text(
                        text = "指定した時間が経過すると自動で再生を停止します（設定した時間は次回もそのまま使えます）",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 時間・分のステッパー選択
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 時間 (0 ~ 12時間)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { inputHours = (inputHours + 1).coerceIn(0, 12) },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(SurfaceElevated, CircleShape)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "+1h", tint = ElectricBlueGlow)
                            }
                            Text(
                                text = "${inputHours}時間",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            IconButton(
                                onClick = { inputHours = (inputHours - 1).coerceIn(0, 12) },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(SurfaceElevated, CircleShape)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "-1h", tint = TextSecondary)
                            }
                        }

                        Text(":", color = TextSecondary, fontSize = 24.sp, fontWeight = FontWeight.Bold)

                        // 分 (0 ~ 59分, 5分単位＆1分単位対応)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { inputMinutes = (inputMinutes + 5) % 60 },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(SurfaceElevated, CircleShape)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "+5m", tint = ElectricBlueGlow)
                            }
                            Text(
                                text = "${inputMinutes}分",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            IconButton(
                                onClick = { inputMinutes = if (inputMinutes >= 5) inputMinutes - 5 else 55 },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(SurfaceElevated, CircleShape)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "-5m", tint = TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 分の微調整スライダー (1分刻み)
                    Slider(
                        value = inputMinutes.toFloat(),
                        onValueChange = { inputMinutes = it.roundToInt().coerceIn(0, 59) },
                        valueRange = 0f..59f,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricBlueGlow,
                            activeTrackColor = ElectricBlueBright,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // クイックプリセットチップ
                    val presets = listOf(
                        0 to 15,
                        0 to 30,
                        0 to 45,
                        1 to 0,
                        1 to 30,
                        2 to 0
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (h, m) ->
                            val isSelected = inputHours == h && inputMinutes == m
                            val label = if (h > 0 && m > 0) "${h}時間${m}分" else if (h > 0) "${h}時間" else "${m}分"
                            val chipShape = RoundedCornerShape(50)
                            Box(
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(if (isSelected) ElectricBlue else SurfaceElevated)
                                    .border(1.dp, if (isSelected) ElectricBlueGlow else CardBorder, chipShape)
                                    .clickable {
                                        inputHours = h
                                        inputMinutes = m
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val totalSecs = (inputHours * 3600L) + (inputMinutes * 60L)
                        if (totalSecs <= 0L) {
                            Toast.makeText(context, "1分以上の時間を設定してください", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        // 設定時間を永続保存（次回開いたときもこの時間がそのままセットされる）
                        savedTimerHours = inputHours
                        savedTimerMinutes = inputMinutes
                        timerPrefs.edit()
                            .putInt("saved_hours", inputHours)
                            .putInt("saved_minutes", inputMinutes)
                            .apply()

                        sleepTimerRemainingSeconds = totalSecs
                        showSleepTimerDialog = false
                        val msg = if (inputHours > 0) "${inputHours}時間${inputMinutes}分後" else "${inputMinutes}分後"
                        Toast.makeText(context, "🌙 ${msg}に再生を自動停止します", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlueBright)
                ) {
                    Text("タイマー開始", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    if (sleepTimerRemainingSeconds > 0L) {
                        TextButton(
                            onClick = {
                                sleepTimerRemainingSeconds = 0L
                                showSleepTimerDialog = false
                                Toast.makeText(context, "おやすみタイマーを解除しました", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("タイマー解除", color = DangerRed, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { showSleepTimerDialog = false }) {
                        Text("閉じる", color = TextSecondary)
                    }
                }
            }
        )
    }

    // アプリ個別音量調整ダイアログ (0% ~ 300%)
    if (showVolumeSheet) {
        AlertDialog(
            onDismissRequest = { showVolumeSheet = false },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            title = {
                Text("KrcPlayer 音量ブースト設定", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text(
                        text = "スマホ本体のメディア音量とは別に、このプレイヤーだけの音量を 0% 〜 300% で個別調整・増幅できます。",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "現在の音量: ${(appVolume * 100).toInt()}%",
                        color = ElectricBlueGlow,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = appVolume,
                        onValueChange = { applyAppVolume(it) },
                        valueRange = 0f..3.0f,
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
                        TextButton(onClick = { applyAppVolume(0f) }) { Text("0%") }
                        TextButton(onClick = { applyAppVolume(1.0f) }) { Text("100%") }
                        TextButton(onClick = { applyAppVolume(2.0f) }) { Text("200%") }
                        TextButton(onClick = { applyAppVolume(3.0f) }) { Text("300%") }
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
