package com.kooric.krcplayer.data

import android.content.Context
import com.kooric.krcplayer.model.VideoItem

class RecentHistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("krc_recent_history", Context.MODE_PRIVATE)

    // 最近再生した動画のIDを最大5件まで順番に保持 (カンマ区切り)
    fun recordPlayedVideo(videoId: Long) {
        if (videoId < 0) return // シークレット内の動画は表の履歴に出さない（プライバシー保護）
        val currentIds = getRecentVideoIds().toMutableList()
        currentIds.remove(videoId)
        currentIds.add(0, videoId)
        val trimmed = currentIds.take(5)
        prefs.edit().putString("recent_ids", trimmed.joinToString(",")).apply()
    }

    fun getRecentVideoIds(): List<Long> {
        val raw = prefs.getString("recent_ids", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun getRecentVideos(allVideos: List<VideoItem>): List<VideoItem> {
        val videoMap = allVideos.associateBy { it.id }
        return getRecentVideoIds().mapNotNull { videoMap[it] }
    }
}
