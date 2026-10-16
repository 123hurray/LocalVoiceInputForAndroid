package com.example.localvoiceime

import java.io.File
import java.security.MessageDigest

object DownloadIntegrity {
    fun verify(file: File, size: Long, sha256: String): Boolean {
        if (file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)
    }

    fun validRange(value: String?, offset: Long, total: Long): Boolean {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value ?: "") ?: return false
        val (start, end, size) = match.destructured
        return start.toLongOrNull() == offset && size.toLongOrNull() == total &&
            end.toLongOrNull()?.let { it >= offset && it < total } == true
    }
}
