package com.iiitnr.inventoryapp.utils

sealed class LaunchResult {
    data object Launched : LaunchResult()

    data object NoAppAvailable : LaunchResult()

    data class Error(
        val message: String,
    ) : LaunchResult()
}

/** Opens a UPI deep link through the operating system's app chooser. */
interface IntentLauncher {
    fun launchUpi(uri: String): LaunchResult
}

expect fun createIntentLauncher(): IntentLauncher
