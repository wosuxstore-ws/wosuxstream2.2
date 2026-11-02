package com.freetv.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Tiny settings store: selected source, custom URL and favorites (stored with logo, so they work across sources). */
object Prefs {
    private lateinit var sp: SharedPreferences
    private var favs: LinkedHashMap<String, Channel>? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("freetv", Context.MODE_PRIVATE)
    }

    var source: Source
        get() = Source(
            sp.getString("src_name", null) ?: Sources.ALL.name,
            sp.getString("src_url", null) ?: Sources.ALL.url
        )
        set(value) {
            sp.edit().putString("src_name", value.name).putString("src_url", value.url).apply()
        }

    var customUrl: String
        get() = sp.getString("custom_url", "") ?: ""
        set(value) {
            sp.edit().putString("custom_url", value).apply()
        }

    fun favorites(): List<Channel> = loadFavs().values.toList()

    fun isFavorite(url: String): Boolean = loadFavs().containsKey(url)

    /** @return true if the channel is now a favorite, false if it was removed. */
    fun toggleFavorite(c: Channel): Boolean {
        val map = loadFavs()
        val added = if (map.containsKey(c.url)) {
            map.remove(c.url)
            false
        } else {
            map[c.url] = c
            true
        }
        save(map)
        return added
    }

    private fun loadFavs(): LinkedHashMap<String, Channel> {
        favs?.let { return it }
        val map = LinkedHashMap<String, Channel>()
        try {
            val arr = JSONArray(sp.getString("favs", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = Channel(
                    name = o.getString("n"),
                    url = o.getString("u"),
                    logo = o.optString("l").takeIf { it.isNotBlank() },
                    group = o.optString("g").takeIf { it.isNotBlank() },
                    referrer = o.optString("r").takeIf { it.isNotBlank() },
                    userAgent = o.optString("a").takeIf { it.isNotBlank() }
                )
                map[c.url] = c
            }
        } catch (e: Exception) {
            // ignore corrupt data
        }
        favs = map
        return map
    }

    private fun save(map: Map<String, Channel>) {
        val arr = JSONArray()
        for (c in map.values) {
            val o = JSONObject()
            o.put("n", c.name)
            o.put("u", c.url)
            c.logo?.let { o.put("l", it) }
            c.group?.let { o.put("g", it) }
            c.referrer?.let { o.put("r", it) }
            c.userAgent?.let { o.put("a", it) }
            arr.put(o)
        }
        sp.edit().putString("favs", arr.toString()).apply()
    }
}
