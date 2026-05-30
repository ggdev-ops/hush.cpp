package myai.hush.engine

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalNativeApi
import myai.hush.*
import cnames.structs.hush_player_t

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual class HushPlayer actual constructor() {
    private val player: CPointer<hush_player_t>?

    init {
        player = hush_player_create()
        if (player == null) {
            throw IllegalStateException("Failed to create Hush player")
        }
    }

    actual fun play(filepath: String): Boolean {
        if (player == null) return false
        return hush_player_play(player, filepath) != 0
    }

    actual fun playBuffer(samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        if (player == null || samples.isEmpty()) return false
        return samples.usePinned { pinned ->
            hush_player_play_buffer(player, pinned.addressOf(0).reinterpret(), count, sampleRate) != 0
        }
    }

    actual fun togglePause() {
        if (player != null) {
            hush_player_toggle_pause(player)
        }
    }

    actual fun stop() {
        if (player != null) {
            hush_player_stop(player)
        }
    }

    actual fun isPlaying(): Boolean {
        if (player == null) return false
        return hush_player_is_playing(player) != 0
    }

    actual fun isFinished(): Boolean {
        if (player == null) return false
        return hush_player_is_finished(player) != 0
    }

    actual fun close() {
        if (player != null) {
            hush_player_destroy(player)
        }
    }
}
