package myai.hush.kmp.permissions

import androidx.compose.runtime.Composable

/**
 * Interface/class for requesting audio recording permissions.
 */
expect class AudioPermissionRequester {
    /**
     * Check if audio permission is granted.
     */
    fun isPermissionGranted(): Boolean

    /**
     * Request the audio permission.
     */
    @Composable
    fun RequestPermission(onResult: (Boolean) -> Unit)
}

