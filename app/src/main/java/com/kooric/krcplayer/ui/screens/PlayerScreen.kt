package com.kooric.krcplayer.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Rational
import android.view.LayoutInflater
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kooric.krcplayer.R
import com.kooric.krcplayer.data.SecretVaultManager
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

    // おやすみタイマー ＆ 音量設定の永続保存用 SharedPreferences
    val timerPrefs = remember {
        context.getSharedPreferences("krc_sleep_timer_prefs", Context.MODE_PRIVATE)
    }
    var savedTimerHours by remember { mutableIntStateOf(timerPrefs.getInt("saved_hours", 0)) }
    var savedTimerMinutes by remember { mutableIntStateOf(timerPrefs.getInt("saved_minutes", 30)) }

    // 次の動画再生時に前回のボリュームが100%を超えていたら100%に戻す設定（デフォルトON）
    var resetVolumeOver100 by remember {
        mutableStateOf(timerPrefs.getBoolean("reset_volume_over_100", true))
    }

    var sleepTimerRemainingSeconds by remember { mutableLongStateOf(0L) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    // アプリ個別音量 (0.0f ~ 5.0f : 0% ~ 500% ブースト対応)
    var appVolume by remember { mutableFloatStateOf(1.0f) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var attachedAudioSessionId by remember { mutableIntStateOf(C.AUDIO_SESSION_ID_UNSET) }

    // 通常リピート ＆ A-B区間リピート状態
    var isRepeatOne by remember { mutableStateOf(false) }
    var abPointA by remember { mutableStateOf<Long?>(null) }
    var abPointB by remember { mutableStateOf<Long?>(null) }

    // 2本指ピンチイン・ピンチアウト拡大縮小（ズーム＆パン）状態
    var videoScale by remember { mutableFloatStateOf(1.0f) }
    var videoPanX by remember { mutableFloatStateOf(0f) }
    var videoPanY by remember { mutableFloatStateOf(0f) }

    // シークレットフォルダ内の暗号化動画もオンザフライ復号で再生できるExoPlayer
    val exoPlayer = remember {
        val dataSourceFactory = SecretVaultManager.createExoDataSourceFactory(context)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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
        val clamped = vol.coerceIn(0f, 5.0f)
        appVolume = clamped
        try {
            exoPlayer.volume = clamped.coerceAtMost(1.0f)
            val currentSessionId = exoPlayer.audioSessionId
            if (clamped > 1.0f && currentSessionId != C.AUDIO_SESSION_ID_UNSET) {
                if (loudnessEnhancer == null || attachedAudioSessionId != currentSessionId) {
                    try {
                        loudnessEnhancer?.release()
                    } catch (_: Exception) {
                    }
                    loudnessEnhancer = LoudnessEnhancer(currentSessionId).apply {
                        enabled = true
                    }
                    attachedAudioSessionId = currentSessionId
                }
                // 100% ~ 500% (1.0f ~ 5.0f) を 0 ~ 4000 mB (+40dB) のゲインにマッピング
                val gainMb = (((clamped - 1.0f) / 4.0f) * 4000).toInt()
                loudnessEnhancer?.setTargetGain(gainMb)
                loudnessEnhancer?.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
            }
        } catch (_: Exception) {
        }
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
    var isDraggingSeekbar by remember { mutableStateOf(false) }

    // 動画切り替え時（ミニプレイヤー再生中に他の動画を開いたときも安全にリセット＆再生開始）
    LaunchedEffect(video.id, video.uri) {
        currentPosition = 0L
        totalDuration = 0L
        abPointA = null
        abPointB = null
        videoScale = 1.0f
        videoPanX = 0f
        videoPanY = 0f
        isControlsVisible = true

        // 設定がONで前回の音量が100%を超えていたら自動的に100%へ戻す
        if (resetVolumeOver100 && appVolume > 1.0f) {
            appVolume = 1.0f
        }

        try {
            val mediaItem = MediaItem.fromUri(video.uri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.repeatMode = if (isRepeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            applyAppVolume(appVolume)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            try {
                exoPlayer.release()
            } catch (_: Exception) {
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = !isMinimized) {
        onMinimizeChange(true)
    }

    // スワイプ・ピンチジェスチャー表示用
    var gestureOverlayTitle by remember { mutableStateOf<String?>(null) }
    var gestureOverlayValue by remember { mutableStateOf<String?>(null) }
    var gestureProgress by remember { mutableFloatStateOf(-1f) }

    // ダブルタップ10秒シークのアニメーション表示用
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

    val animOffsetX by animateFloatAsState(
        targetValue = if (isMinimized) miniOffsetX else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "MiniOffsetX"
    )
    val animOffsetY by animateFloatAsState(
        targetValue = if (isMinimized) miniOffsetY else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "MiniOffsetY"
    )

    // 再生位置更新 ＆ A-B区間リピート監視ループ (120ms周期)
    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val dur = exoPlayer.duration
                    totalDuration = if (dur != C.TIME_UNSET && dur > 0L) dur else 0L
                    applyAppVolume(appVolume)
                }
            }
        }
        exoPlayer.addListener(listener)

        while (true) {
            if (!isDraggingSeekbar) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration
                currentPosition = pos
                totalDuration = if (dur != C.TIME_UNSET && dur > 0L) dur else 0L

                // A-B区間リピートが作動中の場合、B地点に到達したら即座にA地点へ戻す
                val pointA = abPointA
                val pointB = abPointB
                if (pointA != null && pointB != null && pointB > pointA) {
                    if (pos >= pointB || pos < pointA) {
                        exoPlayer.seekTo(pointA)
                        currentPosition = pointA
                    }
                }
            }
            delay(120L)
        }
    }

    LaunchedEffect(isControlsVisible, isMinimized, isDraggingSeekbar) {
        if (isControlsVisible && !isScreenLocked && !isMinimized && !isDraggingSeekbar) {
            delay(3800)
            isControlsVisible = false
        }
    }

    // ===== BoxWithConstraints でフルスクリーン ⇔ 右下ミニプレイヤーを安全かつ滑らかにアニメーション補間 =====
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        val safeMaxWidth = maxWidth.coerceAtLeast(240.dp)
        val safeMaxHeight = maxHeight.coerceAtLeast(160.dp)

        val targetWidth = if (isMinimized) 232.dp else safeMaxWidth
        val targetHeight = if (isMinimized) 154.dp else safeMaxHeight
        val targetPadEnd = if (isMinimized) 16.dp else 0.dp
        val targetPadBottom = if (isMinimized) 24.dp else 0.dp
        val targetCorner = if (isMinimized) 16.dp else 0.dp
        val targetBorderWidth = if (isMinimized) 1.5.dp else 0.dp

        // DampingRatioNoBouncy (1.0f) を使い、0.dpへの遷移時にマイナス値へオーバーシュートするクラッシュを完全防止
        val noBounceSpring = spring<Dp>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )

        val rawWidth by animateDpAsState(targetValue = targetWidth, animationSpec = noBounceSpring, label = "PlayerWidth")
        val rawHeight by animateDpAsState(targetValue = targetHeight, animationSpec = noBounceSpring, label = "PlayerHeight")
        val rawPadEnd by animateDpAsState(targetValue = targetPadEnd, animationSpec = noBounceSpring, label = "PlayerPadEnd")
        val rawPadBottom by animateDpAsState(targetValue = targetPadBottom, animationSpec = noBounceSpring, label = "PlayerPadBottom")
        val rawCorner by animateDpAsState(targetValue = targetCorner, animationSpec = noBounceSpring, label = "PlayerCorner")
        val rawBorder by animateDpAsState(targetValue = targetBorderWidth, animationSpec = noBounceSpring, label = "PlayerBorder")

        // 万が一にも負のDpにならないよう厳密に下限ガード
        val animWidth = rawWidth.coerceIn(180.dp, safeMaxWidth)
        val animHeight = rawHeight.coerceIn(120.dp, safeMaxHeight)
        val animPadEnd = rawPadEnd.coerceAtLeast(0.dp)
        val animPadBottom = rawPadBottom.coerceAtLeast(0.dp)
        val animCorner = rawCorner.coerceAtLeast(0.dp)
        val animBorder = rawBorder.coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .padding(end = animPadEnd, bottom = animPadBottom)
                .offset { IntOffset(animOffsetX.roundToInt(), animOffsetY.roundToInt()) }
                .width(animWidth)
                .height(animHeight)
                .shadow(
                    elevation = if (isMinimized) 20.dp else 0.dp,
                    shape = RoundedCornerShape(animCorner),
                    spotColor = ElectricBlue
                )
                .clip(RoundedCornerShape(animCorner))
                .background(Color.Black)
                .then(
                    if (animBorder > 0.1.dp) {
                        Modifier.border(
                            width = animBorder,
                            color = ElectricBlueBright.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(animCorner)
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ===== 映像表示 ＆ ジェスチャー領域 =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = animCorner, topEnd = animCorner))
                        .background(Color.Black)
                        // シングルタップ ＆ 左右ダブルタップ（10秒戻る/進む ※画面下部・上部では誤発動しないよう制限）
                        .pointerInput(isMinimized, isScreenLocked) {
                            detectTapGestures(
                                onTap = {
                                    if (isMinimized) {
                                        miniOffsetX = 0f
                                        miniOffsetY = 0f
                                        onMinimizeChange(false)
                                    } else {
                                        isControlsVisible = !isControlsVisible
                                    }
                                },
                                onDoubleTap = { offset ->
                                    if (!isMinimized && !isScreenLocked) {
                                        val topIgnorePx = 76.dp.toPx()
                                        val bottomIgnorePx = 165.dp.toPx()
                                        if (offset.y < topIgnorePx || offset.y > size.height - bottomIgnorePx) {
                                            return@detectTapGestures
                                        }
                                        val isLeft = offset.x < size.width / 2
                                        if (isLeft) {
                                            val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                            exoPlayer.seekTo(target)
                                            currentPosition = target
                                            doubleTapSeekSide = -1
                                            doubleTapSeekAccumulated = if (doubleTapSeekSide == -1) doubleTapSeekAccumulated + 10 else 10
                                            doubleTapTriggerTick++
                                        } else {
                                            val durLimit = totalDuration.coerceAtLeast(0L)
                                            val target = if (durLimit > 0L) {
                                                (exoPlayer.currentPosition + 10_000L).coerceAtMost(durLimit)
                                            } else {
                                                exoPlayer.currentPosition + 10_000L
                                            }
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
                        // 1本指スワイプ（音量・明るさ・シーク / ミニ画面移動）＆ 2本指ピンチイン・アウト拡大縮小の統合マルチタッチ処理
                        // ※画面下部（165dp以内）および上部（76dp以内）からの1本指スワイプは完全無効化し、シークバー操作との競合を防止！
                        .pointerInput(isMinimized, isScreenLocked) {
                            if (isMinimized) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    miniOffsetX += dragAmount.x
                                    miniOffsetY += dragAmount.y
                                }
                                return@pointerInput
                            }
                            if (isScreenLocked) return@pointerInput

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startX = down.position.x
                                val startY = down.position.y

                                val topZonePx = 76.dp.toPx()
                                val bottomZonePx = 165.dp.toPx()
                                // 画面上部または画面下部から始まったタッチでは1本指スワイプ（早送り/音量/明るさ）を発動させない
                                val isSingleFingerBlockedZone = startY < topZonePx || startY > (size.height - bottomZonePx)

                                var isMultiTouchZoom = false
                                var isHorizontalSwipe = false
                                var isVerticalSwipe = false

                                do {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }

                                    if (pressedCount >= 2) {
                                        // ===== 2本指ピンチイン・ピンチアウト拡大縮小モード =====
                                        isMultiTouchZoom = true
                                        isHorizontalSwipe = false
                                        isVerticalSwipe = false

                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()

                                        if (zoomChange != 1f || panChange != androidx.compose.ui.geometry.Offset.Zero) {
                                            val newScale = (videoScale * zoomChange).coerceIn(1.0f, 4.0f)
                                            videoScale = newScale

                                            if (newScale > 1.01f) {
                                                val maxPanX = (size.width * (newScale - 1f)) / 2f
                                                val maxPanY = (size.height * (newScale - 1f)) / 2f
                                                videoPanX = (videoPanX + panChange.x).coerceIn(-maxPanX, maxPanX)
                                                videoPanY = (videoPanY + panChange.y).coerceIn(-maxPanY, maxPanY)
                                            } else {
                                                videoPanX = 0f
                                                videoPanY = 0f
                                            }

                                            gestureOverlayTitle = "ピンチズーム"
                                            gestureOverlayValue = "${(videoScale * 100).roundToInt()}%"
                                            gestureProgress = ((videoScale - 1f) / 3f).coerceIn(0f, 1f)
                                        }

                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (pressedCount == 1 && !isMultiTouchZoom && !isSingleFingerBlockedZone && !isDraggingSeekbar) {
                                        // ===== 1本指スワイプ操作（中央エリアのみ有効） =====
                                        val change = event.changes.firstOrNull()
                                        if (change != null && change.positionChanged()) {
                                            val dragAmount = change.position - change.previousPosition
                                            val isLeftSide = startX < size.width / 2

                                            if (!isHorizontalSwipe && !isVerticalSwipe) {
                                                if (abs(dragAmount.x) > abs(dragAmount.y) && abs(dragAmount.x) > 10f) {
                                                    isHorizontalSwipe = true
                                                } else if (abs(dragAmount.y) > abs(dragAmount.x) && abs(dragAmount.y) > 10f) {
                                                    isVerticalSwipe = true
                                                }
                                            }

                                            if (isHorizontalSwipe) {
                                                change.consume()
                                                val seekDelta = (dragAmount.x * 160).toLong()
                                                val durLimit = totalDuration.coerceAtLeast(1L)
                                                val newPos = (exoPlayer.currentPosition + seekDelta).coerceIn(0L, durLimit)
                                                exoPlayer.seekTo(newPos)
                                                currentPosition = newPos
                                                gestureOverlayTitle = if (dragAmount.x >= 0) "早送り >>" else "<< 巻き戻し"
                                                gestureOverlayValue = formatTime(newPos)
                                                gestureProgress = if (totalDuration > 0L) (newPos.toFloat() / totalDuration).coerceIn(0f, 1f) else -1f
                                            } else if (isVerticalSwipe) {
                                                change.consume()
                                                val deltaRatio = -dragAmount.y / size.height.toFloat().coerceAtLeast(1f)
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
                                                    val newVol = (appVolume + deltaRatio * 3.0f).coerceIn(0f, 5.0f)
                                                    applyAppVolume(newVol)
                                                    val percent = (newVol * 100).toInt()
                                                    gestureOverlayTitle = if (percent > 100) "音量ブースト (最大500%)" else "アプリ個別音量"
                                                    gestureOverlayValue = "$percent%"
                                                    gestureProgress = (newVol / 5.0f).coerceIn(0f, 1f)
                                                }
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                gestureOverlayTitle = null
                                gestureOverlayValue = null
                                gestureProgress = -1f
                            }
                        }
                ) {
                    // TextureViewベースのPlayerView（2本指ピンチズームやミニ画面の角丸クリップが100%滑らかに効く）
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.player_texture_view, null) as PlayerView).apply {
                                player = exoPlayer
                                useController = false
                                this.resizeMode = resizeMode
                            }
                        },
                        update = { view ->
                            if (view.player != exoPlayer) {
                                view.player = exoPlayer
                            }
                            view.resizeMode = if (isMinimized) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (isMinimized) 1f else videoScale
                                scaleY = if (isMinimized) 1f else videoScale
                                translationX = if (isMinimized) 0f else videoPanX
                                translationY = if (isMinimized) 0f else videoPanY
                            }
                    )

                    // ===== 縮小時のミニウィンドウ用オーバーレイ（動画の右上に×ボタンを配置） =====
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isMinimized,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(120)),
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

                            // 右上：動画の上に載せる「×（閉じる）」ボタン
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
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isMinimized && doubleTapSeekSide == -1,
                        enter = fadeIn(tween(140)) + scaleIn(spring(stiffness = Spring.StiffnessMedium)),
                        exit = fadeOut(tween(220)),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 48.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ElectricBlue.copy(alpha = 0.38f))
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

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isMinimized && doubleTapSeekSide == 1,
                        enter = fadeIn(tween(140)) + scaleIn(spring(stiffness = Spring.StiffnessMedium)),
                        exit = fadeOut(tween(220)),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 48.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ElectricBlue.copy(alpha = 0.38f))
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

                    // ===== スワイプ・ピンチズームのオーバーレイ表示 =====
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isMinimized && gestureOverlayTitle != null,
                        enter = fadeIn(tween(120)) + scaleIn(),
                        exit = fadeOut(tween(180)) + scaleOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(190.dp)
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
                    androidx.compose.animation.AnimatedVisibility(
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
                                            Color.Black.copy(alpha = 0.78f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.90f)
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

                                val pillShape = RoundedCornerShape(50)

                                // ズーム中なら「100%に戻す」リセットボタンを表示
                                if (videoScale > 1.02f) {
                                    Row(
                                        modifier = Modifier
                                            .padding(end = 6.dp)
                                            .clip(pillShape)
                                            .background(ElectricBlue)
                                            .border(1.dp, ElectricBlueGlow, pillShape)
                                            .clickable {
                                                videoScale = 1.0f
                                                videoPanX = 0f
                                                videoPanY = 0f
                                            }
                                            .padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ZoomOutMap,
                                            contentDescription = "Reset Zoom",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${(videoScale * 100).roundToInt()}%",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // 🌙 おやすみタイマーボタン
                                val timerActive = sleepTimerRemainingSeconds > 0L
                                Row(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(pillShape)
                                        .background(if (timerActive) ElectricBlue else SurfaceElevated.copy(alpha = 0.85f))
                                        .border(1.dp, if (timerActive) ElectricBlueGlow else CardBorder, pillShape)
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

                                // 音量ブーストボタン (0% ~ 500%)
                                Row(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(pillShape)
                                        .background(if (appVolume > 1.0f) ElectricBlue else DeepBlueSurface.copy(alpha = 0.85f))
                                        .border(1.dp, ElectricBlueBright.copy(alpha = 0.7f), pillShape)
                                        .clickable { showVolumeSheet = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Volume",
                                        tint = if (appVolume > 1.0f) Color.White else ElectricBlueGlow,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${(appVolume * 100).roundToInt()}%",
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
                                            val durLimit = totalDuration.coerceAtLeast(0L)
                                            val target = if (durLimit > 0L) {
                                                (exoPlayer.currentPosition + 10_000L).coerceAtMost(durLimit)
                                            } else {
                                                exoPlayer.currentPosition + 10_000L
                                            }
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

                                // ===== 下部コントロール領域（リピート / A-Bリピート / 大型シークバー） =====
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    // リピート機能 ＆ A-B区間リピートバー
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val pillShape = RoundedCornerShape(50)

                                        // 1. 通常リピート（1本ループ ON/OFF）ボタン
                                        Row(
                                            modifier = Modifier
                                                .clip(pillShape)
                                                .background(if (isRepeatOne) ElectricBlue else SurfaceDark.copy(alpha = 0.8f))
                                                .border(
                                                    1.dp,
                                                    if (isRepeatOne) ElectricBlueGlow else CardBorder,
                                                    pillShape
                                                )
                                                .clickable {
                                                    isRepeatOne = !isRepeatOne
                                                    exoPlayer.repeatMode = if (isRepeatOne) {
                                                        Player.REPEAT_MODE_ONE
                                                    } else {
                                                        Player.REPEAT_MODE_OFF
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isRepeatOne) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                                contentDescription = "Repeat",
                                                tint = if (isRepeatOne) Color.White else TextSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isRepeatOne) "リピート ON" else "リピート OFF",
                                                color = if (isRepeatOne) Color.White else TextSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        // 2. A-B 区間リピートボタン
                                        val hasA = abPointA != null
                                        val hasB = abPointB != null
                                        val abLabel = when {
                                            !hasA -> "A-B 区間リピート"
                                            hasA && !hasB -> "A [${formatTime(abPointA!!)}] → Bを決定"
                                            else -> "A-B [${formatTime(abPointA!!)} - ${formatTime(abPointB!!)}] ×"
                                        }

                                        Row(
                                            modifier = Modifier
                                                .clip(pillShape)
                                                .background(
                                                    when {
                                                        hasB -> ElectricBlue
                                                        hasA -> DeepBlueSurface
                                                        else -> SurfaceDark.copy(alpha = 0.8f)
                                                    }
                                                )
                                                .border(
                                                    1.dp,
                                                    if (hasA) ElectricBlueGlow else CardBorder,
                                                    pillShape
                                                )
                                                .clickable {
                                                    val nowPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                                                    when {
                                                        abPointA == null -> {
                                                            abPointA = nowPos
                                                            abPointB = null
                                                            Toast.makeText(
                                                                context,
                                                                "始点 A (${formatTime(nowPos)}) を設定しました。終点 B で再度タップしてください",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        abPointB == null -> {
                                                            if (nowPos <= abPointA!! + 500L) {
                                                                Toast.makeText(
                                                                    context,
                                                                    "終点 B は始点 A より後の位置でタップしてください",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            } else {
                                                                abPointB = nowPos
                                                                Toast.makeText(
                                                                    context,
                                                                    "A-B区間リピートを開始しました",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                        else -> {
                                                            abPointA = null
                                                            abPointB = null
                                                            Toast.makeText(
                                                                context,
                                                                "A-B区間リピートを解除しました",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CompareArrows,
                                                contentDescription = "A-B Repeat",
                                                tint = if (hasA) Color.White else ElectricBlueGlow,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = abLabel,
                                                color = if (hasA) Color.White else TextSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // 時間表記
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

                                    // 大型タッチ判定付きシークバー（高さ52dpの広いタッチ判定領域 ＆ 太いトラックバー）
                                    val safeSliderRatio = if (totalDuration > 0L) {
                                        (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    LargeInteractiveSeekbar(
                                        progress = if (safeSliderRatio.isNaN()) 0f else safeSliderRatio,
                                        abStartRatio = if (totalDuration > 0L && abPointA != null) {
                                            (abPointA!!.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                                        } else null,
                                        abEndRatio = if (totalDuration > 0L && abPointB != null) {
                                            (abPointB!!.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                                        } else null,
                                        onDraggingChange = { dragging ->
                                            isDraggingSeekbar = dragging
                                            if (!dragging) {
                                                gestureOverlayTitle = null
                                                gestureOverlayValue = null
                                                gestureProgress = -1f
                                            }
                                        },
                                        onSeekRatio = { ratio ->
                                            if (totalDuration > 0L) {
                                                val target = (ratio * totalDuration).toLong().coerceIn(0L, totalDuration)
                                                exoPlayer.seekTo(target)
                                                currentPosition = target
                                                gestureOverlayTitle = "シーク移動"
                                                gestureOverlayValue = formatTime(target)
                                                gestureProgress = ratio
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ===== ミニウィンドウ下部バー（タイトル ＆ 再生/一時停止ボタン） =====
                if (isMinimized) {
                    val safeMiniProgress = if (totalDuration > 0L) {
                        (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { if (safeMiniProgress.isNaN()) 0f else safeMiniProgress },
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

    // ===== 🌙 おやすみタイマー設定ダイアログ =====
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

    // アプリ個別音量調整ダイアログ (0% ~ 500% ＆ 100%超リセット設定)
    if (showVolumeSheet) {
        AlertDialog(
            onDismissRequest = { showVolumeSheet = false },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            title = {
                Text("KrcPlayer 音量ブースト (最大500%)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text(
                        text = "スマホ本体のメディア音量とは別に、このプレイヤーだけの音量を 0% 〜 500% で個別調整・超増幅できます。",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "現在の音量: ${(appVolume * 100).roundToInt()}%",
                        color = ElectricBlueGlow,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = appVolume,
                        onValueChange = { applyAppVolume(it) },
                        valueRange = 0f..5.0f,
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
                        TextButton(onClick = { applyAppVolume(0f) }) { Text("0%", fontSize = 12.sp) }
                        TextButton(onClick = { applyAppVolume(1.0f) }) { Text("100%", fontSize = 12.sp) }
                        TextButton(onClick = { applyAppVolume(2.0f) }) { Text("200%", fontSize = 12.sp) }
                        TextButton(onClick = { applyAppVolume(3.0f) }) { Text("300%", fontSize = 12.sp) }
                        TextButton(onClick = { applyAppVolume(5.0f) }) { Text("500%", fontSize = 12.sp) }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 次の動画再生時に100%を超えていたら100%に戻す設定トグル
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceElevated)
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                val nextState = !resetVolumeOver100
                                resetVolumeOver100 = nextState
                                timerPrefs.edit().putBoolean("reset_volume_over_100", nextState).apply()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "次の動画再生時に100%へ戻す",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "前回の音量が100%を超えていた場合、次の動画開始時に自動で100%にリセットします",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = resetVolumeOver100,
                            onCheckedChange = { checked ->
                                resetVolumeOver100 = checked
                                timerPrefs.edit().putBoolean("reset_volume_over_100", checked).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ElectricBlueBright,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = PureBlack
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showVolumeSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlueBright)
                ) {
                    Text("完了", color = Color.White, fontWeight = FontWeight.Bold)
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

/**
 * 判定領域を大きく（高さ52dp）、トラックも太く（通常8dp / 操作時12dp）した高感度シークバー。
 * タップ＆水平ドラッグのポインターイベントを完全消費するため、背面のスワイプ早送りが誤発動しない。
 */
@Composable
private fun LargeInteractiveSeekbar(
    progress: Float,
    abStartRatio: Float?,
    abEndRatio: Float?,
    onDraggingChange: (Boolean) -> Unit,
    onSeekRatio: (Float) -> Unit
) {
    var barWidthPx by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 8.dp,
        animationSpec = tween(150),
        label = "TrackHeight"
    )
    val thumbSize by animateDpAsState(
        targetValue = if (isDragging) 26.dp else 20.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "ThumbSize"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .onSizeChanged { size ->
                barWidthPx = size.width.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val ratio = (offset.x / barWidthPx).coerceIn(0f, 1f)
                    onSeekRatio(ratio)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        isDragging = true
                        onDraggingChange(true)
                        val ratio = (startOffset.x / barWidthPx).coerceIn(0f, 1f)
                        onSeekRatio(ratio)
                    },
                    onDragEnd = {
                        isDragging = false
                        onDraggingChange(false)
                    },
                    onDragCancel = {
                        isDragging = false
                        onDraggingChange(false)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val ratio = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                        onSeekRatio(ratio)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val safeProgress = progress.coerceIn(0f, 1f)

        // 背景トラック
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(SurfaceElevated.copy(alpha = 0.9f))
                .border(0.5.dp, CardBorder, RoundedCornerShape(50))
        )

        // 再生済みアクティブトラック
        Box(
            modifier = Modifier
                .fillMaxWidth(safeProgress)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(ElectricBlue, ElectricBlueGlow)
                    )
                )
        )

        // A-Bリピート区間マーカー表示
        if (abStartRatio != null) {
            val startX = (maxWidth * abStartRatio.coerceIn(0f, 1f)).coerceAtLeast(0.dp)
            val endRatio = abEndRatio?.coerceIn(abStartRatio, 1f) ?: abStartRatio
            val segmentWidth = (maxWidth * (endRatio - abStartRatio)).coerceAtLeast(4.dp)
            Box(
                modifier = Modifier
                    .offset(x = startX)
                    .width(segmentWidth)
                    .height(trackHeight + 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.45f))
            )
        }

        // 大型サム（つまみ）
        val maxThumbOffset = (maxWidth - thumbSize).coerceAtLeast(0.dp)
        val thumbOffset = maxThumbOffset * safeProgress
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .shadow(10.dp, CircleShape, spotColor = ElectricBlueGlow)
                .clip(CircleShape)
                .background(ElectricBlueGlow)
                .border(2.5.dp, Color.White, CircleShape)
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
