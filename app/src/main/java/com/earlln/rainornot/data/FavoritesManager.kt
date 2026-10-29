package com.earlln.rainornot.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class FavoritesManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cctv_favorites", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<CctvData>>() {}.type

    fun getAll(): List<CctvData> = try {
        gson.fromJson<List<CctvData>>(prefs.getString("favorites_list", "[]"), listType).orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<CctvData>) {
        prefs.edit().putString("favorites_list", gson.toJson(list)).apply()
    }

    private fun key(c: CctvData) = c.bestUrl ?: c.name.orEmpty()

    fun isFavorite(cctv: CctvData): Boolean = getAll().any { key(it) == key(cctv) }

    fun add(cctv: CctvData) {
        val list = getAll().toMutableList()
        if (list.any { key(it) == key(cctv) }) return
        if (list.size >= MAX_FAVORITES) list.removeAt(list.lastIndex)
        list.add(cctv)
        save(list)
    }

    fun remove(cctv: CctvData) = save(getAll().filterNot { key(it) == key(cctv) })

    /** @return 토글 후 즐겨찾기 상태 */
    fun toggle(cctv: CctvData): Boolean =
        if (isFavorite(cctv)) { remove(cctv); false } else { add(cctv); true }

    fun moveUp(cctv: CctvData) = move(cctv, -1)
    fun moveDown(cctv: CctvData) = move(cctv, +1)

    private fun move(cctv: CctvData, delta: Int) {
        val list = getAll().toMutableList()
        val i = list.indexOfFirst { key(it) == key(cctv) }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        save(list)
    }

    companion object {
        const val MAX_FAVORITES = 5
    }
}
