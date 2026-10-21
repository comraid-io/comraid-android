# ComRaid Android SDK

Native Android SDK for local notifications/timers and the ComRaid mobile identity loop (**Subscription** register, **Login**, **Logout**, Contact-level push opt-in/out). Vocabulary: **Endpoint** / **Subscription**, `external_user_id`, Identity verification **JWT** (`login(..., jwt)`). Server-side sends use **Message** / **Notification**, **Campaign**, **Single send**, **Broadcast**, and **Multi-send** (see Node SDK / docs).

## Setup

1. Add the library and **Firebase Messaging** (the host app owns the Firebase project).
2. Configure early in `Application.onCreate` with a publishable App ID (`cr_app_…`).

```kotlin
ComRaid.configure(
  applicationContext,
  ComRaidConfiguration(
    appId = "cr_app_your_id",
    // apiBaseUrl = "http://10.0.2.2:8080", // optional; default https://api.comraid.io
    smallIconResourceId = R.drawable.ic_notification,
  ),
)
```

## Identity loop

Request notification permission at an appropriate UI moment. On grant, the SDK fetches the FCM token and registers a Subscription:

```kotlin
ComRaid.requestPermission(activity) { granted ->
  if (granted) {
    // Subscription is registered; store holds subscription_id + credential
  }
}

// Or, if permission is already granted:
ComRaid.register()
```

Forward permission results:

```kotlin
override fun onRequestPermissionsResult(
  requestCode: Int,
  permissions: Array<out String>,
  grantResults: IntArray,
) {
  super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  ComRaid.onRequestPermissionsResult(requestCode, grantResults)
}
```

Login with the product's External user ID (optional Identity verification JWT). Logout makes the Subscription Inactive. Opt-out / opt-in are Contact × push Channel (independent of Inactive):

```kotlin
ComRaid.login("user_123")
ComRaid.login("user_123", jwt = jwtFromYourBackend)

ComRaid.logout()

ComRaid.optOut() // Contact Opted out of the push Channel (all Subscriptions)
ComRaid.optIn()
```

Logout makes the Subscription Inactive; Opted out is Contact × Channel and independent of Inactive.

On FCM token rotation, either call `ComRaid.onPushTokenRefresh()` from your `FirebaseMessagingService.onNewToken`, or declare the open class `com.comraid.android.internal.ComraidMessagingService` (or a subclass) in the host manifest — package `com.comraid.android.internal`, class name `ComraidMessagingService`.

## Local timers

```kotlin
val result = ComRaid.startTimer(
  notification = ComRaidNotification(
    id = "focus-1",
    title = "Focus complete",
    body = "Your timer has finished.",
  ),
  durationMillis = 25 * 60 * 1000L,
)
```

Exact alarms are opt-in: the host must declare the applicable exact-alarm permission, satisfy store policy, and obtain system access. ComRaid defaults to inexact alarms and never adds an exact-alarm permission to the library manifest.

Boot restoration needs the host app to call `ComRaid.configure` from `Application.onCreate` before the boot receiver can restore schedules.

## Notes

- Wire field is `external_user_id` (never `external_id`).
- Logout keeps the stored credential so Contact-level opt-in/out still works while Inactive.
- Remote notification display and delivery ACKs are out of scope for this package version.
