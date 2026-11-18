package com.comraid.android.internal

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.comraid.android.InAppLayout
import com.comraid.android.InAppMessage
import com.comraid.android.InAppMessageHandle
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Loads a message image off the main thread and delivers the bitmap (or null) on the main thread. */
internal fun interface InAppImageLoader {
  fun load(url: String, onLoaded: (Bitmap?) -> Unit)
}

internal object UrlInAppImageLoader : InAppImageLoader {
  override fun load(url: String, onLoaded: (Bitmap?) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    if (!url.startsWith("https://") && !url.startsWith("http://")) {
      main.post { onLoaded(null) }
      return
    }
    thread(name = "comraid-inapp-image", isDaemon = true) {
      val bitmap = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
          connection.inputStream.use { readCapped(it, MAX_IMAGE_BYTES) }?.let { decodeSampledBitmap(it, MAX_IMAGE_DIMENSION) }
        } finally {
          connection.disconnect()
        }
      } catch (_: Exception) {
        null
      } catch (_: OutOfMemoryError) {
        null
      }
      main.post { onLoaded(bitmap) }
    }
  }

  private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
  private const val MAX_IMAGE_DIMENSION = 1080

  private fun readCapped(stream: java.io.InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val read = stream.read(buffer)
      if (read < 0) break
      if (out.size() + read > limit) return null // too large to be a sensible message image
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }
}

/**
 * Decode [bytes] no larger than needed to fill [maxDimension] pixels, so a huge campaign image
 * cannot exhaust memory. Returns null when the bytes are not an image or memory runs out.
 */
internal fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
    sample *= 2
  }
  return try {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
  } catch (_: OutOfMemoryError) {
    null
  }
}

/** Remembers the Activity currently on screen, so a message can be drawn on it. */
internal class InAppActivityTracker {
  @Volatile private var current: java.lang.ref.WeakReference<Activity>? = null

  fun resumed(activity: Activity) {
    current = java.lang.ref.WeakReference(activity)
  }

  fun paused(activity: Activity) {
    if (current?.get() === activity) current = null
  }

  fun current(): Activity? = current?.get()
}

/** The SDK's default layouts, drawn with framework Views in a Dialog on the resumed Activity. */
internal class DefaultInAppDisplay(
  private val activityProvider: () -> Activity?,
  private val imageLoader: InAppImageLoader = UrlInAppImageLoader,
) : InAppDisplay {
  override fun isAvailable(): Boolean {
    val activity = activityProvider() ?: return false
    return !activity.isFinishing && !activity.isDestroyed
  }

  override fun show(message: InAppMessage, handle: InAppMessageHandle): Boolean {
    val activity = activityProvider() ?: return false
    return InAppRenderer.show(activity, message, handle, imageLoader)
  }
}

internal object InAppRenderer {
  const val CLOSE_LABEL = "×"
  private const val MAX_BUTTONS = 2

  /** Draw [message] on [activity]. Returns false (and reports nothing) when it cannot be drawn. */
  fun show(
    activity: Activity,
    message: InAppMessage,
    handle: InAppMessageHandle,
    imageLoader: InAppImageLoader,
  ): Boolean {
    if (activity.isFinishing || activity.isDestroyed) return false
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    // Set before any user-driven close, so only a teardown by the system counts as an interruption.
    val userClosed = java.util.concurrent.atomic.AtomicBoolean(false)
    dialog.setContentView(buildContent(activity, message, handle, imageLoader, dialog) { userClosed.set(true) })
    dialog.setCanceledOnTouchOutside(message.layout == InAppLayout.MODAL)
    // Back or a tap outside the modal closes it without choosing a button: that is a dismissal.
    dialog.setOnCancelListener {
      userClosed.set(true)
      handle.dismissed()
    }
    // A rotation or a finishing Activity dismisses the dialog with no user action: let the message show again.
    dialog.setOnDismissListener {
      if (!userClosed.get()) (handle as? InAppInterruptible)?.interrupted()
    }
    configureWindow(activity, dialog, message.layout)
    try {
      dialog.show()
    } catch (_: WindowManager.BadTokenException) {
      return false
    }
    handle.impression()
    return true
  }

  private fun configureWindow(activity: Activity, dialog: Dialog, layout: InAppLayout) {
    val window = dialog.window ?: return
    val screenWidth = activity.resources.displayMetrics.widthPixels
    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    when (layout) {
      InAppLayout.MODAL -> {
        window.setGravity(Gravity.CENTER)
        window.setLayout((screenWidth * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
      }
      InAppLayout.BANNER_TOP, InAppLayout.BANNER_BOTTOM -> {
        val gravity = if (layout == InAppLayout.BANNER_TOP) Gravity.TOP else Gravity.BOTTOM
        window.setGravity(gravity or Gravity.CENTER_HORIZONTAL)
        window.setLayout(screenWidth - dp(activity, 16), ViewGroup.LayoutParams.WRAP_CONTENT)
        // A banner leaves the screen behind it visible and touchable.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
      }
      InAppLayout.FULLSCREEN -> {
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.setGravity(Gravity.CENTER)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      }
    }
  }

  private fun buildContent(
    activity: Activity,
    message: InAppMessage,
    handle: InAppMessageHandle,
    imageLoader: InAppImageLoader,
    dialog: Dialog,
    markClosedByUser: () -> Unit,
  ): View {
    val padding = dp(activity, 20)
    val root = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, dp(activity, 12), padding, padding)
      background = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = if (message.layout == InAppLayout.FULLSCREEN) 0f else dp(activity, 16).toFloat()
      }
      if (message.layout == InAppLayout.FULLSCREEN) gravity = Gravity.CENTER_VERTICAL
    }

    root.addView(
      TextView(activity).apply {
        text = CLOSE_LABEL
        contentDescription = "Close"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(Color.DKGRAY)
        gravity = Gravity.END
        setOnClickListener {
          markClosedByUser()
          handle.dismissed()
          dialog.dismiss()
        }
      },
      matchWrap(),
    )

    message.imageUrl?.let { url ->
      val image = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
      }
      root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 160)))
      imageLoader.load(url) { bitmap ->
        if (bitmap != null && dialog.isShowing) {
          image.setImageBitmap(bitmap)
          image.visibility = View.VISIBLE
        }
      }
    }

    root.addView(
      TextView(activity).apply {
        text = message.title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
        setPadding(0, dp(activity, 8), 0, dp(activity, 4))
      },
      matchWrap(),
    )
    root.addView(
      TextView(activity).apply {
        text = message.body
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.DKGRAY)
      },
      matchWrap(),
    )

    val buttons = message.buttons.take(MAX_BUTTONS)
    if (buttons.isNotEmpty()) {
      val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(activity, 16), 0, 0)
      }
      for (button in buttons) {
        row.addView(
          Button(activity).apply {
            text = button.label
            setOnClickListener {
              // The host routes the deep link; the SDK reports the click, then closes the message
              // (the manager does not send a dismissal ACK for a close that follows a click).
              markClosedByUser()
              handle.clicked(button.id)
              handle.dismissed()
              dialog.dismiss()
            }
          },
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
      }
      root.addView(row, matchWrap())
    }
    return root
  }

  private fun matchWrap() =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

  private fun dp(context: Context, value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      context.resources.displayMetrics,
    ).toInt()
}
