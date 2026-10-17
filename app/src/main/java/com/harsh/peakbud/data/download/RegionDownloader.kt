package com.harsh.peakbud.data.download

import com.harsh.peakbud.data.regions.RegionStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

data class PendingDownload(val address: String, val host: String, val receivedBytes: Long, val totalBytes: Long?, val canResume: Boolean)

/** User-initiated HTTPS downloads. Strong ETags protect byte-range resumptions. */
class RegionDownloader(
    private val directory: File,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun pending(): List<PendingDownload> = directory.listFiles().orEmpty()
        .filter { it.extension == "properties" && it.length() <= 65_536 }
        .mapNotNull { file ->
            runCatching {
                val props = Properties().apply { file.inputStream().use { load(it) } }
                val address = props.getProperty("address") ?: return@runCatching null
                val url = checkedUrl(address)
                val key = key(address)
                if (file.name != "$key.properties") return@runCatching null
                val part = directory.resolve("$key.part")
                val complete = directory.resolve("$key.zip")
                val bytes = if (complete.isFile) complete.length() else part.length()
                if (bytes <= 0 || bytes > RegionStore.MAX_BYTES) return@runCatching null
                val total = props.getProperty("total")?.toLongOrNull()?.takeIf { it in bytes..RegionStore.MAX_BYTES }
                PendingDownload(address, url.host, bytes, total, complete.isFile || strongEtag(props.getProperty("etag")))
            }.getOrNull()
        }.sortedBy { it.host }

    fun discard(address: String) {
        checkedUrl(address)
        val key = key(address)
        listOf("part", "zip", "properties").forEach { suffix ->
            val file = directory.resolve("$key.$suffix")
            check(!file.exists() || file.delete()) { "Couldn't remove the paused download." }
        }
    }

    fun download(address: String, checkpoint: () -> Unit = {}, progress: (Long, Long?) -> Unit = { _, _ -> }): File {
        var url = checkedUrl(address)
        check(directory.isDirectory || directory.mkdirs())
        val key = key(address)
        val part = directory.resolve("$key.part")
        val metadata = directory.resolve("$key.properties")
        val complete = directory.resolve("$key.zip")
        if (complete.isFile) return complete
        val saved = Properties().apply { if (metadata.isFile) metadata.inputStream().use { load(it) } }
        val savedEtag = saved.getProperty("etag")
        val resumable = part.isFile && part.length() > 0 && part.length() < RegionStore.MAX_BYTES &&
            strongEtag(savedEtag)
        repeat(6) { redirectCount ->
            checkpoint()
            val offset = if (resumable && saved.getProperty("url") == url.toString()) part.length() else 0L
            val connection = connect(url)
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) {
                    connection.setRequestProperty("Range", "bytes=$offset-")
                    connection.setRequestProperty("If-Range", savedEtag)
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(redirectCount < 5) { "Too many download redirects" }
                    url = checkedUrl(URL(url, connection.getHeaderField("Location") ?: error("Missing redirect destination")).toString())
                    return@repeat
                }
                if (status == 416) { part.delete(); metadata.delete(); error("The paused download expired. Tap the download button to start again.") }
                require(status == 200 || status == 206) { "Download server returned HTTP $status" }
                require(connection.getHeaderField("Content-Encoding").let { it == null || it.equals("identity", true) }) { "Unsupported download encoding" }
                var start = 0L
                var expected: Long? = connection.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
                if (status == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(connection.getHeaderField("Content-Range") ?: "")
                        ?: error("Invalid resume response")
                    val (first, last, total) = range.destructured
                    start = first.toLong()
                    expected = total.toLong()
                    if (offset <= 0 || start != offset || last.toLong() < start || last.toLong() != expected - 1 || connection.getHeaderField("ETag") != savedEtag) {
                        part.delete(); metadata.delete(); error("The download changed on the server. Tap the download button to start again.")
                    }
                }
                require(expected == null || expected in 1..RegionStore.MAX_BYTES) { "Region download exceeds 512 MiB" }
                require(directory.usableSpace > (expected?.minus(start) ?: 16_000_000) + 16_000_000) { "Not enough space on your phone for this download." }
                val etag = connection.getHeaderField("ETag")
                Properties().apply {
                    setProperty("address", address)
                    setProperty("url", url.toString())
                    if (etag != null) setProperty("etag", etag)
                    if (expected != null) setProperty("total", expected.toString())
                }
                    .also { props -> metadata.outputStream().use { props.store(it, "PeakBud download resume metadata") } }
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(part, start > 0).use { output ->
                        val bytes = ByteArray(65536)
                        var total = start
                        while (true) {
                            checkpoint()
                            val n = input.read(bytes)
                            if (n < 0) break
                            total += n
                            require(total <= RegionStore.MAX_BYTES && (expected == null || total <= expected)) { "Download exceeded expected size" }
                            output.write(bytes, 0, n)
                            progress(total, expected)
                        }
                        require(expected == null || total == expected) { "Download paused. Tap Resume to continue." }
                    }
                }
                checkpoint()
                check(part.renameTo(complete)) { "Couldn't finish the download. Tap Resume to try again." }
                return complete
            } finally { connection.disconnect() }
        }
        error("Download redirect limit reached")
    }

    companion object {
        private fun key(address: String): String = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun strongEtag(value: String?): Boolean = value != null && value.length >= 2 && value.startsWith('"') && value.endsWith('"')
        private fun checkedUrl(address: String): URL = URL(address).also {
            require(it.protocol == "https" && !it.host.isNullOrBlank() && it.userInfo == null) { "Use an HTTPS region ZIP URL without embedded credentials" }
        }
    }
}
