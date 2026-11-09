package com.snaptube.clone.data.extractor

import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bridges NewPipeExtractor's HTTP abstraction onto OkHttp.
 *
 * NewPipeExtractor performs all of its own request building (headers, cookies,
 * POST bodies) and only delegates the actual network IO to us, so every header
 * and body must be preserved exactly.
 *
 * Transient failures (429 and 5xx responses, IO errors) are retried with
 * backoff because YouTube rate-limits Innertube requests aggressively.
 */
class NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(request: Request): Response {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val data = request.dataToSend()
                val body = data?.toRequestBody(null)

                val builder = OkHttpRequest.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), body)

                request.headers().forEach { (name, values) ->
                    values.forEach { value -> builder.addHeader(name, value) }
                }
                if (request.headers().keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    builder.header("User-Agent", USER_AGENT)
                }

                client.newCall(builder.build()).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if ((response.code == 429 || response.code >= 500) && attempt < MAX_ATTEMPTS - 1) {
                        lastError = IOException("HTTP ${response.code}")
                        backoff(attempt)
                    } else {
                        return Response(
                            response.code,
                            response.message,
                            response.headers.toMultimap(),
                            responseBody,
                            response.request.url.toString()
                        )
                    }
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) backoff(attempt)
            }
        }
        throw lastError ?: IOException("Request failed")
    }

    private fun backoff(attempt: Int) {
        runCatching { Thread.sleep(600L * (attempt + 1)) }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
