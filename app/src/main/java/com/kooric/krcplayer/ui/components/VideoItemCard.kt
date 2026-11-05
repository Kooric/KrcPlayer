package com.kooric.krcplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kooric.krcplayer.model.VideoItem
import com.kooric.krcplayer.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoItemCard(
    video: VideoItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isCurrentlyPlaying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> DeepBlueSurface.copy(alpha = 0.75f)
            isCurrentlyPlaying -> SurfaceElevated
            else -> SurfaceDark
        },
        label = "CardBgColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> ElectricBlueBright
            isCurrentlyPlaying -> ElectricBlue.copy(alpha = 0.7f)
            else -> CardBorder.copy(alpha = 0.5f)
        },
        label = "CardBorderColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "CardScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // サムネイルボックス
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(74.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PureBlack)
        ) {
            VideoThumbnailImage(
                uri = video.uri,
                path = video.path,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize()
            )

            // 下部グラデーション
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
            )

            // 再生中オーバーレイ
            if (isCurrentlyPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ElectricBlue.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(ElectricBlueBright, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Playing",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 再生時間バッジ
            if (video.duration > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(PureBlack.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = video.getFormattedDuration(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // タイトル ＆ メタ情報
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = video.title,
                color = if (isCurrentlyPlaying) ElectricBlueGlow else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = video.getFormattedSize(),
                        color = ElectricBlueGlow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = video.folderName,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 長押し選択モード時のチェックボックスアニメーション
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut()
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = "Select",
                tint = if (isSelected) ElectricBlueBright else TextSecondary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(26.dp)
            )
        }
    }
}
