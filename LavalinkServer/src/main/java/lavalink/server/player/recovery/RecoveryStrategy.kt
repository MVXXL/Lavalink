package lavalink.server.player.recovery

interface RecoveryStrategy {
    fun recover(lastFrame: ByteArray?): ByteArray?
}
