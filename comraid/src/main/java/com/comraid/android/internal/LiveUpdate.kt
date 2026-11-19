package com.comraid.android.internal

/**
 * A Live activity FCM data message (contract keys `cr_activity_*`). The server sends it as a
 * data-only message, so the SDK draws it as an ongoing notification instead of a tray item.
 */
internal object LiveUpdate {
  enum class Op(val wire: String) {
    START("start"),
    UPDATE("update"),
    END("end"),
  }

  data class Message(
    val activityId: String,
    val activityType: String,
    val op: Op,
    val title: String?,
    val body: String?,
    /** 0 to 100, or null when absent or not a whole number in range. */
    val progress: Int?,
    val statusText: String?,
  )

  /** The live update in [data], or null for an ordinary message or a malformed live update. */
  fun fromData(data: Map<String, String>?): Message? {
    if (data == null) return null
    val activityId = data["cr_activity_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val op = Op.values().firstOrNull { it.wire == data["cr_activity_op"]?.trim() } ?: return null
    return Message(
      activityId = activityId,
      activityType = data["cr_activity_type"]?.trim().orEmpty(),
      op = op,
      title = data["cr_activity_title"]?.trim()?.takeIf { it.isNotEmpty() },
      body = data["cr_activity_body"]?.trim()?.takeIf { it.isNotEmpty() },
      progress = data["cr_activity_progress"]?.trim()?.takeIf { PROGRESS.matches(it) }?.toInt()?.takeIf { it in 0..100 },
      statusText = data["cr_activity_status_text"]?.trim()?.takeIf { it.isNotEmpty() },
    )
  }

  private val PROGRESS = Regex("""\d{1,3}""")
}
