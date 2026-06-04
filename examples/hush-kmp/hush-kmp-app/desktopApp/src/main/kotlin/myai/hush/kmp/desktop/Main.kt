package myai.hush.kmp.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import myai.hush.kmp.*

fun main() = application {
    val mediaManager = JvmMediaManager()
    val permissionRequester = JvmAudioPermissionRequester()
    val viewModel = AppViewModel(mediaManager)
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Hush KMP App"
    ) {
        App(viewModel, permissionRequester)
    }
}
