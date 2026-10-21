package com.comraid.android.internal

internal data class HttpRequest(
  val method: String,
  val path: String,
  val headers: Map<String, String>,
  val body: String?,
)

internal data class HttpResponse(
  val statusCode: Int,
  val body: String,
  val headers: Map<String, String> = emptyMap(),
)

internal fun interface HttpTransport {
  fun execute(request: HttpRequest): HttpResponse
}
