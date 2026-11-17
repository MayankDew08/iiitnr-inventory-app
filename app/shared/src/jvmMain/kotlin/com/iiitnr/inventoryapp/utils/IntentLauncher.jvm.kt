package com.iiitnr.inventoryapp.utils

private class UnsupportedIntentLauncher : IntentLauncher {
    override fun launchUpi(uri: String): LaunchResult = LaunchResult.NoAppAvailable
}

actual fun createIntentLauncher(): IntentLauncher = UnsupportedIntentLauncher()
