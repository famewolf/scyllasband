package org.scyllasband.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelInstallException(message: String) : Exception(message)

/** Downloads and verifies the pinned LiteRT bundle into app-private storage. */
class ModelManager(
    private val context: Context,
    private val baseUrl: String = "https://huggingface.co/spybyscript/scyllasband/resolve/main",
    private val revision: String = "v4-20261106-apple",
) {
    val bundleDir: File
        get() = File(context.noBackupFilesDir, "scyllasband/models/litert")

    fun isInstalled(): Boolean =
        File(bundleDir, "manifest.json").isFile && File(bundleDir, "litert/vocoder.tflite").isFile

    suspend fun ensureInstalled(onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext bundleDir

        val sums = JSONObject(fetch("$baseUrl/SHA256SUMS.json?revision=$revision"))
        val prefix = "litert/"
        val entries = sums.keys().asSequence()
            .filter { it.startsWith(prefix) && !it.endsWith("/") }
            .map { path ->
                val meta = JSONObject(sums.getString(path))
                Triple(path.removePrefix(prefix), meta.getString("sha256"), meta.optLong("bytes"))
            }
            .toList()
        require(entries.isNotEmpty()) { "No litert files in SHA256SUMS.json" }

        val totalBytes = entries.sumOf { it.third.coerceAtLeast(0) }
        val staging = File(context.noBackupFilesDir, "scyllasband/staging")
        staging.mkdirs()

        var doneBytes = 0L
        fun report(fileBytes: Long) {
            if (totalBytes > 0) onProgress(((doneBytes + fileBytes) * 100 / totalBytes).coerceAtMost(100).toInt())
        }

        entries.forEachIndexed { index, (name, expected, size) ->
            val target = File(staging, name)
            target.parentFile?.mkdirs()
            if (target.isFile && sha256(target) == expected) {
                doneBytes += size.coerceAtLeast(target.length())
                report(0)
                return@forEachIndexed
            }

            var lastError: Exception? = null
            var ok = false
            repeat(4) { attempt ->
                if (ok) return@repeat
                try {
                    downloadToFile("$baseUrl/$prefix$name?revision=$revision", target, expected) { fileBytes ->
                        report(fileBytes)
                    }
                    ok = true
                } catch (e: Exception) {
                    lastError = e
                    // A failed attempt may leave a partial file; delete it so
                    // the next attempt starts clean.
                    target.delete()
                    if (attempt < 3) {
                        onProgress(((doneBytes) * 100 / totalBytes.coerceAtLeast(1)).coerceAtMost(100).toInt())
                        delay(1000L * (attempt + 1))
                    }
                }
            }
            if (!ok) throw ModelInstallException("Failed to download $name: ${lastError?.message}")
            doneBytes += size.coerceAtLeast(target.length())
            report(0)
            onProgress(((index + 1) * 100 / entries.size))
        }

        val parent = bundleDir.parentFile!!
        parent.mkdirs()
        if (!staging.renameTo(bundleDir)) {
            staging.copyRecursively(bundleDir, overwrite = true)
            staging.deleteRecursively()
        }
        if (!isInstalled()) throw ModelInstallException("Installation incomplete")
        onProgress(100)
        bundleDir
    }

    fun delete() {
        bundleDir.deleteRecursively()
    }

    private fun fetch(url: String): String = String(fetchBytes(url))

    /** Downloads to disk with resume support and verifies sha256 before returning. */
    private fun downloadToFile(url: String, target: File, expectedSha: String, onChunk: (Long) -> Unit) {
        var offset = if (target.isFile()) target.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        conn.connect()
        if (conn.responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            throw ModelInstallException("HTTP ${conn.responseCode} for $url")
        }
        val append = conn.responseCode == HttpURLConnection.HTTP_PARTIAL && offset > 0
        if (!append) {
            offset = 0
            target.delete()
        }
        FileOutputStream(target, append).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    offset += read
                    onChunk(offset)
                }
            }
        }
        val actual = sha256(target)
        if (actual != expectedSha) throw ModelInstallException("Checksum mismatch")
    }

    /** Small files only (SHA256SUMS.json). */
    private fun fetchBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw ModelInstallException("HTTP ${conn.responseCode} for $url")
        }
        return conn.inputStream.use { it.readBytes() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
