package com.example.peakmildeffort

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/** Keeps map tiles the user has actually viewed so they still show offline. Never prefetches (Thunderforest forbids it on the free plan). */
class TileCache(private val dir: File, private val maxBytes: Long = 50L * 1024 * 1024) {

    private val lock = Any()

    fun handles(request: WebResourceRequest): Boolean =
        request.method == "GET" && request.url.host == HOST && TILE_PATH.matches(request.url.path.orEmpty())

    fun load(request: WebResourceRequest): WebResourceResponse {
        val path = request.url.path.orEmpty()
        // The path is validated by TILE_PATH, so this is safe as a file name; the API key in the query is not part of it.
        val file = File(dir, path.removePrefix("/").replace('/', '_'))
        synchronized(lock) {
            if (file.isFile && file.setLastModified(System.currentTimeMillis())) return image(FileInputStream(file))
        }
        return fetch(request, file)
    }

    private fun fetch(request: WebResourceRequest, file: File): WebResourceResponse {
        val connection = (URL(request.url.toString()).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", request.requestHeaders["User-Agent"] ?: "OlManMuz/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Referer", request.requestHeaders["Referer"] ?: "https://${REFERER_HOST}/")
            val code = connection.responseCode
            if (code != 200) return failure(if (code in 300..399) 502 else code)
            val bytes = connection.inputStream.use { readLimited(it) } ?: return failure(502)
            synchronized(lock) {
                dir.mkdirs()
                val temp = File(dir, "${file.name}.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(file)) temp.delete()
                prune()
            }
            return image(ByteArrayInputStream(bytes))
        } catch (e: java.io.IOException) {
            return failure(504)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_TILE_BYTES) return null
        }
    }

    private fun prune() {
        val files = dir.listFiles { file -> file.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes * 9 / 10) break
            total -= file.length()
            file.delete()
        }
    }

    private fun image(stream: java.io.InputStream) =
        WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"), stream)

    private fun failure(code: Int) =
        WebResourceResponse("text/plain", "utf-8", code, "Tile unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val HOST = "api.thunderforest.com"
        private const val REFERER_HOST = "appassets.androidplatform.net"
        private const val MAX_TILE_BYTES = 512 * 1024
        private val TILE_PATH = Regex("^/outdoors/\\d{1,2}/\\d{1,7}/\\d{1,7}(@2x)?\\.png$")
    }
}
