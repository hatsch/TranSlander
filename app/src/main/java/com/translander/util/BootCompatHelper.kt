package com.translander.util

import android.os.Build

/**
 * Helper for Android 14+ boot compatibility.
 * Centralizes API level checks for foreground service (FGS) restrictions.
 *
 * ## Android Version Restrictions Matrix
 *
 * | Android | API | FGS Restrictions at Boot |
 * |---------|-----|--------------------------|
 * | 8.0-13  | 26-33 | None - FGS starts directly from BOOT_COMPLETED |
 * | 14      | 34    | `microphone` FGS type blocked from boot/background |
 * | 15      | 35    | Same as 14, plus `dataSync` has 6-hour timeout |
 * | 16      | 36    | Same restrictions as Android 15 |
 *
 * ## Why Check for API 34?
 *
 * Android 14 (API 34, UPSIDE_DOWN_CAKE) was the first version to restrict
 * starting certain FGS types from background contexts like BOOT_COMPLETED.
 * This restriction applies to:
 * - `microphone` (used by FloatingMicService)
 * - `camera`
 * - `mediaProjection`
 *
 * Android 15 (API 35) added the 6-hour timeout for `dataSync` FGS type,
 * which is why AudioMonitorService uses `specialUse` instead.
 *
 * The check for API < 34 covers all Android versions from 14 onwards,
 * including 15 and 16.
 *
 * @see <a href="https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start">FGS Background Start Restrictions</a>
 * @see <a href="https://developer.android.com/about/versions/15/changes/foreground-service-types">Android 15 FGS Changes</a>
 */
object BootCompatHelper {
    /**
     * Whether the app can start foreground services with restricted types
     * (microphone, dataSync) from BOOT_COMPLETED receiver.
     *
     * Returns `true` for Android 8.0-13 (API 26-33) where FGS can start directly.
     * Returns `false` for Android 14+ (API 34+) where a notification must be
     * shown and the user must tap to start services.
     */
    val canStartFgsFromBoot: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
