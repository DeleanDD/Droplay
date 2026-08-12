package com.droplay.tv.data

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

internal object Network {
    fun text(url: String): String = open(url).bufferedReader().use { it.readText() }

    fun open(url: String): java.io.InputStream {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "DROPLAY/1.0 AndroidTV")
        val stream = BufferedInputStream(connection.inputStream)
        return if (url.substringBefore('?').endsWith(".gz", true)) GZIPInputStream(stream) else stream
    }
}
