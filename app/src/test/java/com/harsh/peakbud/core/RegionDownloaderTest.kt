package com.harsh.peakbud.core

import com.harsh.peakbud.data.download.RegionDownloader
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RegionDownloaderTest {
    private class Response(private val status: Int, private val headers: Map<String, String>, private val bytes: ByteArray) : HttpURLConnection(URL("https://example.test/region.zip")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("peakbud-download-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    @Test fun interruptedDownloadResumesOnlyWithMatchingStrongEtag() = withDirectory { directory ->
        var request = 0
        lateinit var resumed: Response
        val downloader = RegionDownloader(directory) {
            if (request++ == 0) Response(200, mapOf("Content-Length" to "10", "ETag" to "\"v1\""), "01234".toByteArray())
            else Response(206, mapOf("Content-Length" to "5", "Content-Range" to "bytes 5-9/10", "ETag" to "\"v1\""), "56789".toByteArray()).also { resumed = it }
        }
        assertTrue(runCatching { downloader.download("https://example.test/region.zip") }.isFailure)
        val file = downloader.download("https://example.test/region.zip")
        assertEquals("bytes=5-", resumed.getRequestProperty("Range"))
        assertEquals("\"v1\"", resumed.getRequestProperty("If-Range"))
        assertEquals("0123456789", file.readText())
    }
    @Test fun serverIgnoringRangeRestartsInsteadOfAppending() = withDirectory { directory ->
        var request = 0
        val downloader = RegionDownloader(directory) {
            if (request++ == 0) Response(200, mapOf("Content-Length" to "10", "ETag" to "\"v1\""), "01234".toByteArray())
            else Response(200, mapOf("Content-Length" to "10", "ETag" to "\"v2\""), "abcdefghij".toByteArray())
        }
        runCatching { downloader.download("https://example.test/region.zip") }
        assertEquals("abcdefghij", downloader.download("https://example.test/region.zip").readText())
    }
    @Test fun httpAndDowngradeRedirectsAreRejected() = withDirectory { directory ->
        val downloader = RegionDownloader(directory) { Response(302, mapOf("Location" to "http://example.test/plain.zip"), byteArrayOf()) }
        assertTrue(runCatching { downloader.download("http://example.test/region.zip") }.isFailure)
        assertTrue(runCatching { downloader.download("https://example.test/region.zip") }.isFailure)
    }
    @Test fun oversizedDownloadIsRejectedBeforeWritingBody() = withDirectory { directory ->
        val downloader = RegionDownloader(directory) { Response(200, mapOf("Content-Length" to "999999999"), byteArrayOf()) }
        assertTrue(runCatching { downloader.download("https://example.test/region.zip") }.isFailure)
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "zip" })
    }
    @Test fun unfinishedTransferIsDiscoverableAfterProcessRecreation() = withDirectory { directory ->
        val address = "https://example.test/region.zip"
        val initial = RegionDownloader(directory) { Response(200, mapOf("Content-Length" to "10", "ETag" to "\"v1\""), "01234".toByteArray()) }
        assertTrue(runCatching { initial.download(address) }.isFailure)
        val recovered = RegionDownloader(directory).pending().single()
        assertEquals(address, recovered.address)
        assertEquals("example.test", recovered.host)
        assertEquals(5L, recovered.receivedBytes)
        assertEquals(10L, recovered.totalBytes)
        assertTrue(recovered.canResume)
    }
    @Test fun weakEtagDoesNotOfferByteRangeResumeAfterRecreation() = withDirectory { directory ->
        val address = "https://example.test/region.zip"
        val initial = RegionDownloader(directory) { Response(200, mapOf("Content-Length" to "10", "ETag" to "W/\"v1\""), "01234".toByteArray()) }
        runCatching { initial.download(address) }
        assertFalse(RegionDownloader(directory).pending().single().canResume)
        lateinit var response: Response
        val restored = RegionDownloader(directory) { Response(200, mapOf("Content-Length" to "10", "ETag" to "W/\"v1\""), "abcdefghij".toByteArray()).also { response = it } }
        assertEquals("abcdefghij", restored.download(address).readText())
        assertNull(response.getRequestProperty("Range"))
    }
    @Test fun discardRemovesSavedBytesAndResumeMetadata() = withDirectory { directory ->
        val address = "https://example.test/region.zip"
        val downloader = RegionDownloader(directory) { Response(200, mapOf("Content-Length" to "10", "ETag" to "\"v1\""), "01234".toByteArray()) }
        runCatching { downloader.download(address) }
        assertEquals(1, downloader.pending().size)
        RegionDownloader(directory).discard(address)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun completedArchiveRemainsDiscoverableUntilInstallationFinishes() = withDirectory { directory ->
        val address = "https://example.test/region.zip"
        val downloader = RegionDownloader(directory) { Response(200, mapOf("Content-Length" to "10"), "0123456789".toByteArray()) }
        downloader.download(address)
        val recovered = RegionDownloader(directory)
        assertEquals(10L, recovered.pending().single().receivedBytes)
        recovered.discard(address)
        assertTrue(recovered.pending().isEmpty())
    }
}
