package com.iiitnr.inventoryapp.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import java.lang.ref.WeakReference

private class AndroidIntentLauncher {
    companion object {
        var activityRef: WeakReference<Activity>? = null
    }

    fun launch(uri: String): LaunchResult {
        val activity = activityRef?.get() ?: return LaunchResult.NoAppAvailable
        val viewIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (viewIntent.resolveActivity(activity.packageManager) == null) {
            LaunchResult.NoAppAvailable
        } else {
            try {
                activity.startActivity(Intent.createChooser(viewIntent, "Pay with UPI"))
                LaunchResult.Launched
            } catch (e: ActivityNotFoundException) {
                LaunchResult.Error(e.message ?: "Could not open a UPI app")
            } catch (e: SecurityException) {
                LaunchResult.Error(e.message ?: "Could not open a UPI app")
            }
        }
    }
}

fun setIntentLauncherActivity(activity: Activity) {
    AndroidIntentLauncher.activityRef = WeakReference(activity)
}

actual fun createIntentLauncher(): IntentLauncher =
    object : IntentLauncher {
        override fun launchUpi(uri: String): LaunchResult = AndroidIntentLauncher().launch(uri)
    }
