package myai.hush.kmp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import myai.hush.kmp.App
import myai.hush.kmp.AppViewModel
import myai.hush.kmp.getMediaManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mediaManager = getMediaManager(this)
        val viewModel = AppViewModel(mediaManager)
        val permissionRequester = myai.hush.kmp.permissions.AudioPermissionRequester(this)
        
        setContent {
            App(viewModel, permissionRequester)
        }
    }
}

