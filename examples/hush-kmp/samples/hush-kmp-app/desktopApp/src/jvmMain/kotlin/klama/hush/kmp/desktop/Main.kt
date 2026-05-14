package klama.hush.kmp.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import klama.hush.kmp.App
import klama.hush.kmp.AppViewModel
import klama.hush.kmp.getMediaManager
import klama.hush.audio.JvmAudioRecorder

fun main() = application {
    val mediaManager = getMediaManager()
    val recorder = JvmAudioRecorder()
    val viewModel = AppViewModel(recorder, mediaManager)
    val permissionRequester = klama.hush.kmp.permissions.AudioPermissionRequester()
    
    Window(onCloseRequest = ::exitApplication, title = "Hush KMP Desktop") {
        App(viewModel, permissionRequester)
    }
}

