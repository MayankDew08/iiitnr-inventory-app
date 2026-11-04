import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.iiitnr.inventoryapp.data.auth.GoogleWebSignInHelper
import com.iiitnr.inventoryapp.di.initKoin
import com.iiitnr.inventoryapp.shared.App
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalWasmJsInterop::class)
private fun reloadAndClearCache() {
    js("caches.keys().then(keys => Promise.all(keys.map(k => caches.delete(k)))).then(() => location.reload(true))")
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin {}

    val googleSignInHelper = GoogleWebSignInHelper()
    val scope = CoroutineScope(Dispatchers.Default)

    ComposeViewport(document.body!!) {
        AppTheme {
            App(
                isWeb = true,
                onUpdateAction = { reloadAndClearCache() },
                onGoogleSignInClick = { callback ->
                    scope.launch {
                        val idToken = googleSignInHelper.signIn()
                        callback(idToken)
                    }
                },
            )
        }
    }
}
