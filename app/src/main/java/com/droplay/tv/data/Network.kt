package com.droplay.tv.data

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import kotlin.random.Random
import java.util.zip.GZIPInputStream

internal object Network {
    fun text(url: String): String = open(url).bufferedReader().use { it.readText() }

    fun open(url: String): java.io.InputStream {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 45_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "DROPLAY/1.0 AndroidTV")
                val status = connection.responseCode
                if (status !in 200..299) {
                    connection.disconnect()
                    throw HttpStatusException(status)
                }
                val stream = BufferedInputStream(connection.inputStream)
                return if (url.substringBefore('?').endsWith(".gz", true)) GZIPInputStream(stream) else stream
            } catch (error: Throwable) {
                last = error
                if (!isTransient(error) || attempt == 2 || Thread.currentThread().isInterrupted) throw error
                Thread.sleep((400L shl attempt) + Random.nextLong(100L, 350L))
            }
        }
        throw last ?: IOException("Falha de rede")
    }

    internal fun isTransient(error: Throwable): Boolean = when (error) {
        is SocketTimeoutException, is UnknownHostException -> true
        is HttpStatusException -> error.status == 408 || error.status == 429 || error.status in 500..599
        else -> error is IOException && error !is javax.net.ssl.SSLException
    }
}

internal class HttpStatusException(val status: Int) : IOException("HTTP $status")
