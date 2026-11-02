package com.freetv.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val channels = MutableLiveData<List<Channel>>(emptyList())
    val loading = MutableLiveData(false)
    val message = MutableLiveData<String?>()
    val source = MutableLiveData(Prefs.source)

    var query: String = ""
    var favoritesOnly: Boolean = false

    private var job: Job? = null

    init {
        load(force = false)
    }

    fun setSource(s: Source) {
        Prefs.source = s
        source.value = s
        channels.value = emptyList()
        favoritesOnly = false
        load(force = false)
    }

    /**
     * Shows the saved copy instantly (works offline), then refreshes from the internet
     * when forced or when the saved copy is older than a few hours.
     */
    fun load(force: Boolean) {
        job?.cancel()
        val s = source.value ?: Sources.ALL
        job = viewModelScope.launch {
            loading.value = true
            try {
                val cache = cacheFile(s)
                var shown = false

                if (cache.exists()) {
                    val cached = withContext(Dispatchers.IO) { parseFile(cache) }
                    if (cached.isNotEmpty()) {
                        channels.value = cached
                        shown = true
                    }
                }

                val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > STALE_MS
                if (force || stale) {
                    try {
                        // download() returns false when the server answered 304 (unchanged)
                        val fresh = withContext(Dispatchers.IO) {
                            val changed = download(s.url, cache)
                            if (changed || !shown) parseFile(cache) else null
                        }
                        if (fresh != null) {
                            channels.value = fresh
                            if (fresh.isEmpty()) {
                                message.value = getApplication<Application>().getString(R.string.err_empty)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val app = getApplication<Application>()
                        message.value = if (shown) {
                            app.getString(R.string.err_refresh)
                        } else {
                            app.getString(R.string.err_load, e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            } finally {
                if (isActive) loading.value = false
            }
        }
    }

    private fun cacheFile(s: Source): File =
        File(getApplication<Application>().filesDir, "pl_" + Integer.toHexString(s.url.hashCode()) + ".m3u")

    private fun parseFile(f: File): List<Channel> =
        try {
            f.bufferedReader().use { M3uParser.parse(it) }
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Downloads the playlist with the shared OkHttp client (OkHttp follows redirects itself).
     * The ETag is kept in a sidecar file next to the cache file (name + ".etag") and sent back
     * as If-None-Match while that cache file still exists.
     *
     * @return true = a new file was saved, false = server said 304 (unchanged, cache file touched).
     */
    private fun download(urlStr: String, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val etagFile = File(dest.parentFile, dest.name + ".etag")

        // An ETag only makes sense while we still hold the file it belongs to.
        val oldEtag: String? = if (dest.length() > 0L && etagFile.exists()) {
            try {
                etagFile.readText().trim().takeIf { t -> t.isNotEmpty() && t.all { it in ' '..'~' } }
            } catch (e: IOException) {
                null
            }
        } else {
            null
        }

        val request = Request.Builder().url(urlStr).apply {
            if (oldEtag != null) header("If-None-Match", oldEtag)
        }.build()

        val newEtag: String? = Net.client.newCall(request).execute().use { response ->
            if (response.code == 304 && oldEtag != null) {
                dest.setLastModified(System.currentTimeMillis())
                return false
            }
            if (response.code != 200) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty playlist")
            body.byteStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            response.header("ETag")
        }

        if (tmp.length() == 0L) {
            tmp.delete()
            throw IOException("Empty playlist")
        }
        etagFile.delete() // the old ETag belongs to the old file: drop it before the file is replaced
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        newEtag?.trim()?.takeIf { it.isNotEmpty() }?.let { etagFile.writeText(it) }
        return true
    }

    private companion object {
        const val STALE_MS = 6L * 60 * 60 * 1000
    }
}
