package com.jdandroid.engine

import android.content.Context
import android.content.Intent

/**
 * Intent that opens the app's launcher activity from a notification. Resolved
 * through the package manager so the engine does not depend on the UI layer.
 */
internal fun Context.appLaunchIntent(): Intent =
    packageManager.getLaunchIntentForPackage(packageName)
        ?: Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
