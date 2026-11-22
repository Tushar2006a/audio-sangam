package com.example.audioonly

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** The extractor needs us to provide the "download a web page" part. This does it with OkHttp. */
class AppDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()

        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(url)
            .header("User-Agent", USER_AGENT)

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) builder.addHeader(name, value)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("The site asked for a captcha (too many requests)", url)
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString()
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
