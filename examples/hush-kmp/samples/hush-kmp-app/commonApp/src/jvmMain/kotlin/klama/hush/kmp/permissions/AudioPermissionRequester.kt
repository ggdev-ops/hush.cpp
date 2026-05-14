package klama.hush.kmp.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Desktop implementation of AudioPermissionRequester (Always granted).
 */
actual class AudioPermissionRequester {
    actual fun isPermissionGranted(): Boolean = true

    @Composable
    actual fun RequestPermission(onResult: (Boolean) -> Unit) {
        LaunchedEffect(Unit) {
            onResult(true)
        }
    }
}

