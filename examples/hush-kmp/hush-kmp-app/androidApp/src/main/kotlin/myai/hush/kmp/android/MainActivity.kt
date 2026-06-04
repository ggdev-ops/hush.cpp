package myai.hush.kmp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import myai.hush.kmp.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mediaManager = AndroidMediaManager(applicationContext)
        val permissionRequester = AndroidAudioPermissionRequester(this)
        val viewModel = AppViewModel(mediaManager)
        
        setContent {
            App(viewModel, permissionRequester)
        }
    }
}
