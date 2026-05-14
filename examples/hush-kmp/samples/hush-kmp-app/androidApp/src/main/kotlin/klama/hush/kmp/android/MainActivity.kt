package klama.hush.kmp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import klama.hush.kmp.App
import klama.hush.kmp.AppViewModel
import klama.hush.kmp.getMediaManager
import klama.hush.audio.AndroidAudioRecorder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mediaManager = getMediaManager(this)
        val recorder = AndroidAudioRecorder(this)
        val viewModel = AppViewModel(recorder, mediaManager)
        val permissionRequester = klama.hush.kmp.permissions.AudioPermissionRequester(this)
        
        setContent {
            App(viewModel, permissionRequester)
        }
    }
}

