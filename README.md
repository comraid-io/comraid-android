# ComRaid Android SDK

Native Android SDK for push Subscriptions, remote notification display, delivery ACKs, and local timers. Vocabulary: **Endpoint** / **Subscription**, `external_user_id`, Identity verification **JWT** (`login(..., jwt)`), Contact **Attributes**, Subscription **Properties**. Maven coordinate: `com.comraid:comraid`.

## Setup

1. Add the library and **Firebase Messaging** (the host app owns the Firebase project).
2. Configure early in `Application.onCreate` with a publishable App ID (`cr_app_…`). Channels are created here **before** permission / token acquisition (Android 13+).

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

Declare `com.comraid.android.internal.ComraidMessagingService` (or a subclass) in the host manifest for FCM token rotation, remote display (including action buttons), and durable delivered/opened ACKs.

## Identity loop

```kotlin
ComRaid.requestPermission(activity) { granted -> /* … */ }
// or ComRaid.register() when permission is already granted

ComRaid.login("user_123")
ComRaid.login("user_123", jwt = jwtFromYourBackend)
ComRaid.logout()
ComRaid.optOut()
ComRaid.optIn()
```

Forward permission results via `ComRaid.onRequestPermissionsResult`.

## Properties and Attributes

```kotlin
ComRaid.setDeviceProperties(
  DeviceProperties(appVersion = "1.2.0", model = "Pixel 8", osVersion = "14"),
)
// included on the next register / token refresh

ComRaid.login("user_123")
ComRaid.setAttributes(mapOf("plan" to "premium"))
```

## Delivery ACKs

Remote messages enqueue durable delivered/opened ACKs (persisted SharedPreferences queue with Retry-After / backoff). Flush works from the messaging service without a foreground `ComRaid` singleton as long as `configure` has run at least once on the device.

```kotlin
ComRaid.acknowledgeDelivered(deliveryId, attributionToken)
ComRaid.acknowledgeOpened(deliveryId, attributionToken, actionId = "accept")
ComRaid.flushAcks()
```

## Deep links and rich fields

API `content.push.deep_link` arrives in FCM data as `cr_deep_link`. On notification open, read it from the payload map passed through `EXTRA_DATA_JSON` (tap / action handlers) or from `RemoteMessage.data` when you own presentation.

- When FCM includes a system `notification` block, Android applies sound / tag / notification count from the provider; the host still reads `cr_deep_link` on open for in-app navigation.
- When the SDK builds the tray notification (data-first payloads or action buttons), the content intent uses `cr_deep_link` when it is a valid URI and launches it after the opened ACK. Host apps still own intent-filter / routing setup.

Other rich fields (`sound`, `badge`, `thread_id`, `subtitle`) are primarily provider/OS presentation; `subtitle` is iOS-oriented and ignored on Android system trays.

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
- Package namespace is `com.comraid.android`; never `com.comraide`.
