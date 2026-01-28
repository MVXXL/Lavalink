package lavalink.server.player.recovery

class RepeatLastFrameRecovery : RecoveryStrategy {
    override fun recover(lastFrame: ByteArray?): ByteArray? = lastFrame
}
