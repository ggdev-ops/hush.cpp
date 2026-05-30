package myai.hush.kmp.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import myai.hush.kmp.App
import myai.hush.kmp.AppViewModel
import myai.hush.kmp.getMediaManager

fun main() = application {
    val mediaManager = getMediaManager()
    val viewModel = AppViewModel(mediaManager)
    val permissionRequester = myai.hush.kmp.permissions.AudioPermissionRequester()
    
    Window(onCloseRequest = ::exitApplication, title = "Hush KMP Desktop") {
        App(viewModel, permissionRequester)
    }
}

