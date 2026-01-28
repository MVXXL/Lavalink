package lavalink.server.player.recovery

class ComfortNoiseRecovery : RecoveryStrategy {
    override fun recover(lastFrame: ByteArray?): ByteArray? = COMFORT_NOISE_FRAME

    companion object {
        // Opus comfort-noise frame commonly used for Discord.
        private val COMFORT_NOISE_FRAME = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
    }
}
