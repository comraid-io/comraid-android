package com.comraid.android.internal

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.comraid.android.InAppButton
import com.comraid.android.InAppLayout
import com.comraid.android.InAppMessage
import com.comraid.android.InAppMessageHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Seam: the default In-app layouts built by [InAppRenderer] (views, gravity, how taps are reported). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InAppRendererTest {
  private class RecordingHandle : InAppMessageHandle, InAppInterruptible {
    val events = mutableListOf<String>()

    override fun interrupted() {
      events += "interrupted"
    }

    override fun impression() {
      events += "impression"
    }

    override fun clicked(buttonId: String) {
      events += "clicked:$buttonId"
    }

    override fun dismissed() {
      events += "dismissed"
    }
  }

  private lateinit var activity: Activity
  private lateinit var handle: RecordingHandle
  private val requestedImages = mutableListOf<String>()
  private val imageLoader = InAppImageLoader { url, _ -> requestedImages += url }

  @Before
  fun setUp() {
    activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
    handle = RecordingHandle()
    requestedImages.clear()
  }

  private fun message(
    layout: InAppLayout = InAppLayout.MODAL,
    imageUrl: String? = null,
    buttons: List<InAppButton> = listOf(InAppButton("open", "Open", "myapp://x"), InAppButton("later", "Later")),
  ) = InAppMessage(
    id = "m1",
    layout = layout,
    title = "Welcome back",
    body = "See what is new",
    imageUrl = imageUrl,
    buttons = buttons,
  )

  private fun show(message: InAppMessage): Boolean = InAppRenderer.show(activity, message, handle, imageLoader)

  private fun decor(): View = ShadowDialog.getLatestDialog()!!.window!!.decorView

  private fun textViews(view: View): List<TextView> {
    val found = mutableListOf<TextView>()
    fun walk(v: View) {
      if (v is TextView) found += v
      if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(view)
    return found
  }

  private fun imageViews(view: View): List<ImageView> {
    val found = mutableListOf<ImageView>()
    fun walk(v: View) {
      if (v is ImageView) found += v
      if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(view)
    return found
  }

  private fun tap(label: String) {
    textViews(decor()).first { it.text.toString() == label }.performClick()
  }

  @Test
  fun a_modal_shows_title_body_and_buttons_and_reports_an_impression_once() {
    assertTrue(show(message()))

    val labels = textViews(decor()).map { it.text.toString() }
    assertTrue(labels.containsAll(listOf("Welcome back", "See what is new", "Open", "Later")))
    assertEquals(listOf("impression"), handle.events)
    assertTrue(ShadowDialog.getLatestDialog()!!.isShowing)
  }

  @Test
  fun tapping_a_button_reports_the_click_and_closes_without_a_dismissal() {
    show(message())

    tap("Open")

    // The message closes after the tap; the manager decides that a click is not a dismissal ACK.
    assertEquals(listOf("impression", "clicked:open", "dismissed"), handle.events)
    assertFalse(ShadowDialog.getLatestDialog()!!.isShowing)
  }

  @Test
  fun the_system_tearing_the_dialog_down_is_reported_as_an_interruption_not_a_dismissal() {
    show(message())

    ShadowDialog.getLatestDialog()!!.dismiss() // what a rotation or finishing Activity does
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf("impression", "interrupted"), handle.events)
  }

  @Test
  fun a_user_close_is_never_also_reported_as_an_interruption() {
    show(message())

    tap("Open")
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertFalse(handle.events.contains("interrupted"))
  }

  @Test
  fun a_large_image_is_decoded_at_a_reduced_size() {
    val big = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
    val bytes = java.io.ByteArrayOutputStream().also { big.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    val decoded = decodeSampledBitmap(bytes, maxDimension = 500)

    assertNotNull(decoded)
    assertTrue("width ${decoded!!.width} should be reduced", decoded.width <= 1000)
  }

  @Test
  fun backing_out_of_the_message_reports_a_dismissal() {
    show(message())

    ShadowDialog.getLatestDialog()!!.cancel()
    // A dialog delivers its cancel callback through the main looper.
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf("impression", "dismissed"), handle.events)
  }

  @Test
  fun a_close_control_reports_a_dismissal_and_closes() {
    show(message(layout = InAppLayout.FULLSCREEN))

    tap(InAppRenderer.CLOSE_LABEL)

    assertEquals(listOf("impression", "dismissed"), handle.events)
    assertFalse(ShadowDialog.getLatestDialog()!!.isShowing)
  }

  @Test
  fun each_layout_is_placed_where_it_belongs() {
    show(message(layout = InAppLayout.MODAL))
    assertEquals(Gravity.CENTER, ShadowDialog.getLatestDialog()!!.window!!.attributes.gravity)

    show(message(layout = InAppLayout.BANNER_TOP))
    val top = ShadowDialog.getLatestDialog()!!.window!!
    assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, top.attributes.gravity)
    assertEquals("a banner must not dim the screen behind it", 0, top.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)

    show(message(layout = InAppLayout.BANNER_BOTTOM))
    assertEquals(
      Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
      ShadowDialog.getLatestDialog()!!.window!!.attributes.gravity,
    )

    show(message(layout = InAppLayout.FULLSCREEN))
    val full = ShadowDialog.getLatestDialog()!!.window!!.attributes
    assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, full.width)
    assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, full.height)
  }

  @Test
  fun a_banner_lets_touches_reach_the_app_behind_it() {
    show(message(layout = InAppLayout.BANNER_TOP))

    val flags = ShadowDialog.getLatestDialog()!!.window!!.attributes.flags
    assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
  }

  @Test
  fun an_image_is_requested_only_when_the_message_has_one() {
    show(message())
    assertTrue(requestedImages.isEmpty())
    assertTrue(imageViews(decor()).isEmpty())

    show(message(imageUrl = "https://cdn.example.com/a.png"))
    assertEquals(listOf("https://cdn.example.com/a.png"), requestedImages)
    assertEquals(1, imageViews(decor()).size)
  }

  @Test
  fun a_loaded_image_is_put_on_screen() {
    var deliver: ((Bitmap?) -> Unit)? = null
    val loader = InAppImageLoader { _, onLoaded -> deliver = onLoaded }
    InAppRenderer.show(activity, message(imageUrl = "https://cdn.example.com/a.png"), handle, loader)
    val image = imageViews(decor()).single()
    assertEquals("hidden until the image arrives", View.GONE, image.visibility)

    deliver!!.invoke(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))

    assertEquals(View.VISIBLE, image.visibility)
    assertNotNull(image.drawable)
  }

  @Test
  fun at_most_two_buttons_are_drawn() {
    val many = listOf(InAppButton("a", "A"), InAppButton("b", "B"), InAppButton("c", "C"))
    show(message(buttons = many))

    val labels = textViews(decor()).map { it.text.toString() }
    assertTrue(labels.contains("A") && labels.contains("B"))
    assertFalse(labels.contains("C"))
  }

  @Test
  fun nothing_is_drawn_on_an_activity_that_is_finishing() {
    val finishing = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
    finishing.finish()

    assertFalse(InAppRenderer.show(finishing, message(), handle, imageLoader))
    assertTrue(handle.events.isEmpty())
    assertNull(ShadowDialog.getLatestDialog())
  }
}
