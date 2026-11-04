package com.comraid.android.internal

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

internal class UrlConnectionHttpTransport(
  private val apiBaseUrl: String,
) : HttpTransport {
  override fun execute(request: HttpRequest): HttpResponse {
    val base = apiBaseUrl.trimEnd('/')
    val url = URL(base + request.path)
    val connection = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = request.method
      doInput = true
      connectTimeout = 30_000
      readTimeout = 30_000
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Content-Type", "application/json")
      request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
      if (request.body != null) {
        doOutput = true
      }
    }
    try {
      request.body?.let { body ->
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
      }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val responseBody = stream?.use { input ->
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
      } ?: ""
      val headers = buildMap {
        connection.headerFields?.forEach { (key, values) ->
          if (key != null && !values.isNullOrEmpty()) {
            put(key, values.last())
          }
        }
      }
      return HttpResponse(statusCode = code, body = responseBody, headers = headers)
    } finally {
      connection.disconnect()
    }
  }
}
