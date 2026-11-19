package com.comraid.android

/** How an In-app message is laid out. The SDK draws these natively; a [InAppMessagePresenter] can draw its own. */
enum class InAppLayout(val wire: String) {
  MODAL("modal"),
  BANNER_TOP("banner_top"),
  BANNER_BOTTOM("banner_bottom"),
  FULLSCREEN("fullscreen"),
  ;

  companion object {
    fun fromWire(value: String?): InAppLayout? = entries.firstOrNull { it.wire == value }
  }
}

/** A button on an In-app message. The host app routes [deepLink]; the SDK never opens it. */
data class InAppButton(
  val id: String,
  val label: String,
  val deepLink: String? = null,
)

/** One In-app message as received from ComRaid. */
data class InAppMessage(
  val id: String,
  val layout: InAppLayout,
  val title: String,
  val body: String,
  val imageUrl: String? = null,
  val buttons: List<InAppButton> = emptyList(),
  /** Start of the display window in epoch milliseconds; null means no start. */
  val startAtMillis: Long? = null,
  /** End of the display window in epoch milliseconds; null means no end. */
  val endAtMillis: Long? = null,
  /** True for a Dashboard Test send: shown normally, but the SDK makes no ACKs for it. */
  val isTest: Boolean = false,
)

/**
 * Reports what happened to one In-app message. A [InAppMessagePresenter] calls these as the
 * user sees, taps or dismisses its own UI; the default layouts call them for you.
 * Calling them for a Test send is safe: no ACK is sent.
 */
interface InAppMessageHandle {
  /** The message is on screen. Records an impression once. */
  fun impression()

  /** The user tapped the button with [buttonId]. Unknown ids are ignored. */
  fun clicked(buttonId: String)

  /** The user closed the message without (or after) tapping a button. Records a dismissal once. */
  fun dismissed()
}

/**
 * Draws In-app messages in place of the SDK's default layouts. Register one with
 * `ComRaid.setInAppMessagePresenter`; the SDK then draws nothing itself and you report
 * what happened through [handle]. [triggers] are the values set with `ComRaid.addTrigger`.
 */
fun interface InAppMessagePresenter {
  fun present(message: InAppMessage, handle: InAppMessageHandle, triggers: Map<String, String>)
}

/**
 * Observes the life of In-app messages (display and dismissal) and button taps. It cannot
 * change or suppress a message. Every method has a no-op default.
 */
interface InAppMessageListener {
  fun willDisplay(message: InAppMessage) = Unit

  fun didDisplay(message: InAppMessage) = Unit

  fun willDismiss(message: InAppMessage) = Unit

  fun didDismiss(message: InAppMessage) = Unit

  /** A button was tapped. Route [InAppButton.deepLink] yourself: the SDK never opens it. */
  fun didClick(message: InAppMessage, button: InAppButton) = Unit
}
