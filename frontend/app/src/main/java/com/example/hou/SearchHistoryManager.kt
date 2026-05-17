package com.example.hou

import android.content.Context
import androidx.core.content.edit

/**
 * 검색 기록을 SharedPreferences에 저장/불러오기/삭제합니다.
 * 최신순 정렬, 중복 제거, 최대 30개 보관.
 */
class SearchHistoryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "search_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_SIZE = 30
        private const val SEPARATOR = "||"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getHistory(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun addHistory(query: String) {
        if (query.isBlank()) return
        val current = getHistory().toMutableList()
        current.remove(query)          // 중복 제거
        current.add(0, query)          // 최신순 맨 앞
        val trimmed = current.take(MAX_SIZE)
        prefs.edit { putString(KEY_HISTORY, trimmed.joinToString(SEPARATOR)) }
    }

    fun removeHistory(query: String) {
        val current = getHistory().toMutableList()
        current.remove(query)
        prefs.edit { putString(KEY_HISTORY, current.joinToString(SEPARATOR)) }
    }

    fun clearAll() {
        prefs.edit { remove(KEY_HISTORY) }
    }
}
