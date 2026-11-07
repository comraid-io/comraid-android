package com.comraid.android.internal

import android.content.Context
import android.os.Build
import com.comraid.android.DeviceProperties
import java.util.Locale
import java.util.TimeZone

fun interface DeviceInfoProvider {
  fun collect(): DeviceProperties
}

internal class AndroidDeviceInfoProvider(
  private val context: Context,
  private val sdkVersion: String = SdkVersion.VALUE,
) : DeviceInfoProvider {
  override fun collect(): DeviceProperties {
    val locale = Locale.getDefault()
    val appVersion = runCatching {
      context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { !it.isNullOrBlank() }
    return DeviceProperties(
      appVersion = appVersion,
      sdkVersion = sdkVersion,
      model = Build.MODEL?.takeIf { it.isNotBlank() },
      os = "Android",
      osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() },
      locale = locale.toLanguageTag().takeIf { it.isNotBlank() },
      timezone = TimeZone.getDefault().id.takeIf { it.isNotBlank() },
      language = locale.language.takeIf { it.isNotBlank() },
      country = locale.country.takeIf { it.isNotBlank() },
    )
  }
}

internal fun mergeDeviceProperties(
  auto: DeviceProperties,
  override: DeviceProperties?,
): DeviceProperties {
  if (override == null) return auto
  return DeviceProperties(
    appVersion = override.appVersion ?: auto.appVersion,
    sdkVersion = override.sdkVersion ?: auto.sdkVersion,
    model = override.model ?: auto.model,
    os = override.os ?: auto.os,
    osVersion = override.osVersion ?: auto.osVersion,
    locale = override.locale ?: auto.locale,
    timezone = override.timezone ?: auto.timezone,
    language = override.language ?: auto.language,
    country = override.country ?: auto.country,
    extras = auto.extras + override.extras,
  )
}
