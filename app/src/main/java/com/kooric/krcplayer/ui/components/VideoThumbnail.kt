package com.kooric.krcplayer.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kooric.krcplayer.data.SecretVaultManager
import com.kooric.krcplayer.ui.theme.ElectricBlue
import com.kooric.krcplayer.ui.theme.SurfaceDark
import com.kooric.krcplayer.ui.theme.SurfaceElevated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 6

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun get(key: String): Bitmap? = memoryCache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        if (get(key) == null) {
            memoryCache.put(key, bitmap)
        }
    }

    suspend fun loadVideoThumbnail(context: Context, uri: Uri, path: String): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = if (path.isNotEmpty()) path else uri.toString()
        get(cacheKey)?.let { return@withContext it }

        var bitmap: Bitmap? = null

        // 1. Android 10 (API 29) 以降の公式 loadThumbnail (MediaStore URI の場合に最速)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            try {
                bitmap = context.contentResolver.loadThumbnail(uri, Size(480, 270), null)
            } catch (_: Exception) {
            }
        }

        // 2. MediaMetadataRetriever によるフレーム抽出 (暗号化されたシークレット動画もオンザフライ復号で対応)
        if (bitmap == null) {
            val retriever = MediaMetadataRetriever()
            try {
                val file = if (path.isNotEmpty()) File(path) else null
                if (file != null && file.exists()) {
                    if (SecretVaultManager.isFileEncrypted(file)) {
                        retriever.setDataSource(SecretVaultManager.createMediaDataSource(context, file))
                    } else {
                        retriever.setDataSource(path)
                    }
                } else {
                    retriever.setDataSource(context, uri)
                }
                bitmap = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L)
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

        // 3. 最終フォールバック: ThumbnailUtils
        if (bitmap == null && path.isNotEmpty()) {
            val file = File(path)
            if (file.exists() && !SecretVaultManager.isFileEncrypted(file)) {
                try {
                    @Suppress("DEPRECATION")
                    bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
                } catch (_: Exception) {
                }
            }
        }

        bitmap?.let {
            put(cacheKey, it)
        }
        bitmap
    }
}

@Composable
fun VideoThumbnailImage(
    uri: Uri,
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheKey = if (path.isNotEmpty()) path else uri.toString()
    var bitmap by remember(cacheKey) { mutableStateOf(ThumbnailCache.get(cacheKey)) }

    LaunchedEffect(uri, path) {
        if (bitmap == null) {
            bitmap = ThumbnailCache.loadVideoThumbnail(context, uri, path)
        }
    }

    Crossfade(targetState = bitmap, label = "ThumbnailCrossfade", modifier = modifier) { loadedBitmap ->
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(SurfaceDark, SurfaceElevated)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = ElectricBlue.copy(alpha = 0.4f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
