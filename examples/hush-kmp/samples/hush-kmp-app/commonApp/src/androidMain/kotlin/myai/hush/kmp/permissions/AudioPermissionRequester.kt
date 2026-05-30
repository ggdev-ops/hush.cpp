package myai.hush.kmp.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android implementation of AudioPermissionRequester.
 */
actual class AudioPermissionRequester(private val context: Context) {
    actual fun isPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    actual fun RequestPermission(onResult: (Boolean) -> Unit) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            onResult(isGranted)
        }

        LaunchedEffect(Unit) {
            if (!isPermissionGranted()) {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                onResult(true)
            }
        }
    }
}

