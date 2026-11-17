package com.iiitnr.inventoryapp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.iiitnr.inventoryapp.data.auth.GoogleSignInHelper
import com.iiitnr.inventoryapp.di.initKoin
import com.iiitnr.inventoryapp.shared.App
import com.iiitnr.inventoryapp.ui.platform.ImagePickerLauncher
import com.iiitnr.inventoryapp.ui.platform.exportComponentsCsvAndroid
import com.iiitnr.inventoryapp.ui.platform.setImagePickerActivity
import com.iiitnr.inventoryapp.ui.theme.IIITNRInventoryAppTheme
import com.iiitnr.inventoryapp.ui.theme.rememberThemeMode
import com.iiitnr.inventoryapp.ui.theme.resolveIsDark
import com.iiitnr.inventoryapp.utils.setIntentLauncherActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext

class MainActivity : ComponentActivity() {
    private lateinit var googleSignInHelper: GoogleSignInHelper
    private var onGoogleSignInResult: ((String?) -> Unit)? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var pendingCsvImportCallback: ((String?) -> Unit)? = null

    private val csvImportLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val callback = pendingCsvImportCallback
            pendingCsvImportCallback = null
            if (callback == null) return@registerForActivityResult

            if (uri == null) {
                callback(null)
                return@registerForActivityResult
            }

            val content =
                try {
                    contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                } catch (_: Exception) {
                    null
                }
            callback(content)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webClientId = resources.getString(R.string.google_web_client_id)
        googleSignInHelper = GoogleSignInHelper(this, webClientId)
        setImagePickerActivity(this)
        setIntentLauncherActivity(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enableEdgeToEdge()
        }

        initKoin {
            androidContext(this@MainActivity)
        }

        setContent {
            IIITNRInventoryAppTheme(darkTheme = rememberThemeMode().resolveIsDark()) {
                ImagePickerLauncher()
                App(
                    onGoogleSignInClick = { callback ->
                        onGoogleSignInResult = callback
                        coroutineScope.launch {
                            val idToken = googleSignInHelper.signIn()
                            onGoogleSignInResult?.invoke(idToken)
                            onGoogleSignInResult = null
                        }
                    },
                    onExportComponentsCsv = { csvContent ->
                        exportComponentsCsvAndroid(
                            context = this@MainActivity,
                            filename = "components.csv",
                            content = csvContent,
                        )
                    },
                    onExportRequestsCsv = { csvContent ->
                        exportComponentsCsvAndroid(
                            context = this@MainActivity,
                            filename = "requests.csv",
                            content = csvContent,
                        )
                    },
                    onImportComponentsCsv = { completion ->
                        pendingCsvImportCallback = completion
                        csvImportLauncher.launch("text/*")
                    },
                )
            }
        }
    }
}
