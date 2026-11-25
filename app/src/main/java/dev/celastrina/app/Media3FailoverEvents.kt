package dev.celastrina.app

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.celastrina.core.failover.PlayerFault
import dev.celastrina.core.failover.PlayerEvent

/**
 * The Media3 adapter for Failover: turns [player]'s callbacks into the Core's [PlayerEvent]s. The player's position,
 * reported every second, is sent by the player screen. Media3 has already retried loading by the time it reports an error.
 */
class Media3FailoverEvents(private val player: Player, private val report: (PlayerEvent) -> Unit) : Player.Listener {

    override fun onRenderedFirstFrame() = report(PlayerEvent.FirstFrame)

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING && player.playWhenReady) report(PlayerEvent.Buffering)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        when {
            !playWhenReady -> report(PlayerEvent.Paused)
            player.playbackState == Player.STATE_BUFFERING -> report(PlayerEvent.Buffering)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) report(PlayerEvent.Playing)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // Media3 tells of a seek before the buffering it causes.
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) report(PlayerEvent.Seeked)
    }

    override fun onPlayerError(error: PlaybackException) = report(PlayerEvent.Failed(error.toPlayerFault()))
}

private fun PlaybackException.toPlayerFault(): PlayerFault = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlayerFault.Http
    in 2000..2999 -> PlayerFault.Network
    in 3000..3999,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> PlayerFault.UnsupportedFormat
    in 4000..4999 -> PlayerFault.Decoder
    else -> PlayerFault.Other
}
