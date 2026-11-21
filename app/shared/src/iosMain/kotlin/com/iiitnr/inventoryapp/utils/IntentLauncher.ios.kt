package com.iiitnr.inventoryapp.utils

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@OptIn(ExperimentalForeignApi::class)
private class IosIntentLauncher : IntentLauncher {
    override fun launchUpi(uri: String): LaunchResult {
        val url = NSURL.URLWithString(uri) ?: return LaunchResult.Error("Invalid payment link")
        val application = UIApplication.sharedApplication
        if (!application.canOpenURL(url)) return LaunchResult.NoAppAvailable

        return if (application.openURL(url)) {
            LaunchResult.Launched
        } else {
            LaunchResult.Error("Could not open a UPI app")
        }
    }
}

actual fun createIntentLauncher(): IntentLauncher = IosIntentLauncher()
