package dev.trueform.player.playback

import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player the media session sees (docs/plan/02-playback-engine.md T5): shuffle and repeat are the logical
 * queue's, so Bluetooth, cars and the notification show and change the real state. Everything else forwards.
 */
class SessionPlayer(player: Player, private val queue: QueueController) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    init {
        queue.onShuffleChanged = { on ->
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED).build())
            for (l in listeners) {
                l.onShuffleModeEnabledChanged(on)
                l.onEvents(this, events)
            }
        }
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    override fun getShuffleModeEnabled() = queue.state.value.shuffle
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = queue.setShuffle(shuffleModeEnabled)
    override fun setRepeatMode(repeatMode: Int) = queue.setRepeat(
        when (repeatMode) { Player.REPEAT_MODE_ONE -> Repeat.ONE; Player.REPEAT_MODE_ALL -> Repeat.ALL; else -> Repeat.OFF })

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().add(Player.COMMAND_SET_SHUFFLE_MODE).add(Player.COMMAND_SET_REPEAT_MODE).build()

    override fun isCommandAvailable(command: Int) =
        command == Player.COMMAND_SET_SHUFFLE_MODE || command == Player.COMMAND_SET_REPEAT_MODE || super.isCommandAvailable(command)
}
