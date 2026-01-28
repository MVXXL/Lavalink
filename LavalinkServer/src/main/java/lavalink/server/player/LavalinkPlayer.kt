/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.api.IPlayer
import io.netty.buffer.ByteBuf
import lavalink.server.config.AudioRecoveryConfig
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer
import lavalink.server.player.filters.FilterChain
import lavalink.server.player.recovery.ComfortNoiseRecovery
import lavalink.server.player.recovery.RecoveryStrategy
import lavalink.server.player.recovery.RepeatLastFrameRecovery
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.media.OpusAudioFrameProvider
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class LavalinkPlayer(
    override val socketContext: SocketContext,
    override val guildId: Long,
    private val serverConfig: ServerConfig,
    private val audioRecoveryConfig: AudioRecoveryConfig,
    audioPlayerManager: AudioPlayerManager,
    pluginInfoModifiers: List<AudioPluginInfoModifier>
) : AudioEventAdapter(), IPlayer {
    private val buffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize())
    private val mutableFrame = MutableAudioFrame().apply { setBuffer(buffer) }

    val audioLossCounter = AudioLossCounter()
    var endMarkerHit = false
    var filters: FilterChain = FilterChain()
        set(value) {
            audioPlayer.setFilterFactory(value.takeIf { it.isEnabled })
            field = value
        }

    private val eventEmitter = EventEmitter(audioPlayerManager, this, pluginInfoModifiers)

    override val audioPlayer: AudioPlayer = audioPlayerManager.createPlayer().also {
        it.addListener(this)
        it.addListener(eventEmitter)
        it.addListener(audioLossCounter)
    }

    companion object {
        private val log = LoggerFactory.getLogger(LavalinkPlayer::class.java)
    }

    private var updateFuture: ScheduledFuture<*>? = null
    private var diagnosticFuture: ScheduledFuture<*>? = null
    @Volatile
    private var provider: Provider? = null
    @Volatile
    private var nextQueued: Boolean = false
    @Volatile
    private var preloadRequested: Boolean = false
    @Volatile
    private var nextTrack: AudioTrack? = null
    @Volatile
    private var nextTrackEncoded: String? = null
    private val preloadAtMs = 12_000L
    private val swapAtMs = 300L

    override val isPlaying: Boolean
        get() = audioPlayer.playingTrack != null && !audioPlayer.isPaused

    override val track: AudioTrack?
        get() = audioPlayer.playingTrack

    @Volatile
    private var lastActiveMs: Long = System.currentTimeMillis()

    fun getLastActiveMs() = lastActiveMs

    fun touchActive() {
        lastActiveMs = System.currentTimeMillis()
    }

    fun getProviderBufferedMs(): Int {
        return provider?.getBufferedMs() ?: 0
    }

    fun destroy() {
        audioPlayer.destroy()
    }

    fun provideTo(connection: MediaConnection) {
        val provider = Provider(connection)
        this.provider = provider
        connection.audioSender = provider
        if (audioPlayer.playingTrack != null) {
            provider.onTrackStart()
        }
    }

    override fun play(track: AudioTrack) {
        audioPlayer.playTrack(track)
        SocketServer.sendPlayerUpdate(socketContext, this)
        touchActive()
    }

    override fun stop() {
        audioPlayer.stopTrack()
        updateFuture?.cancel(false)
        diagnosticFuture?.cancel(false)
        diagnosticFuture = null
        clearNextTrackSlot()
        provider?.onTrackEnd()
        touchActive()
    }

    override fun setPause(pause: Boolean) {
        audioPlayer.isPaused = pause
        touchActive()
    }

    override fun seekTo(position: Long) {
        val track = audioPlayer.playingTrack ?: throw RuntimeException("Can't seek when not playing anything")
        track.position = position
    }

    override fun setVolume(volume: Int) {
        audioPlayer.volume = volume
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        // 2025-07-31 changed from !! to ? due to possible race condition (or general condition) where
        // updateFuture has not be initialised yet somehow.
        updateFuture?.cancel(false)
        diagnosticFuture?.cancel(false)
        diagnosticFuture = null
        nextQueued = false
        preloadRequested = false
        provider?.onTrackEnd()
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        nextQueued = false
        preloadRequested = false

        if (updateFuture?.isCancelled == false) {
            return
        }

        updateFuture = socketContext.playerUpdateService.scheduleAtFixedRate(
            { SocketServer.sendPlayerUpdate(socketContext, this) },
            0,
            serverConfig.playerUpdateInterval.toLong(),
            TimeUnit.SECONDS
        )
        provider?.onTrackStart()

        if (serverConfig.audioDiagnostics && serverConfig.diagnosticsIntervalSec > 0) {
            diagnosticFuture?.cancel(false)
            diagnosticFuture = socketContext.playerUpdateService.scheduleAtFixedRate({
                val p = provider ?: return@scheduleAtFixedRate
                log.info(
                    "[diag g={}] buf={}ms target={}ms preroll={}ms q={} underruns={} fill={}us frames={} recLeft={}",
                    guildId,
                    p.getBufferedMs(),
                    p.getTargetBufferMs(),
                    p.getMinPrerollMs(),
                    p.frameQueueSize(),
                    p.getUnderruns(),
                    TimeUnit.NANOSECONDS.toMicros(p.getLastFillNs()),
                    p.getLastFillFrames(),
                    p.getRecoveryLeft()
                )
            }, 0, serverConfig.diagnosticsIntervalSec.toLong(), TimeUnit.SECONDS)
        }
    }

    fun onTrackStuck() {
        provider?.onStuck()
    }

    fun hintPreloadNext() {
        if (preloadRequested) return
        preloadRequested = true
        eventEmitter.sendPreloadHint(this)
    }

    fun trySwapNextSoon(): Boolean {
        val next = synchronized(this) {
            if (audioPlayer.isPaused) return false
            takeNextTrack()
        } ?: return false

        // remove marker to avoid repeated triggers
        audioPlayer.playingTrack?.setMarker(null)

        audioPlayer.playTrack(next)
        SocketServer.sendPlayerUpdate(socketContext, this)
        touchActive()
        return true
    }

    fun setNextTrack(encoded: String, track: AudioTrack) {
        synchronized(this) {
            nextTrackEncoded = encoded
            nextTrack = track
            nextQueued = true
        }
    }

    fun clearNextTrackSlot() {
        synchronized(this) {
            nextTrack = null
            nextTrackEncoded = null
            nextQueued = false
        }
    }

    private fun takeNextTrack(): AudioTrack? {
        synchronized(this) {
            val next = nextTrack ?: return null
            nextTrack = null
            nextTrackEncoded = null
            nextQueued = false
            return next
        }
    }

    private inner class Provider(connection: MediaConnection?) : OpusAudioFrameProvider(connection) {
        private val frameQueue = ArrayDeque<ByteArray>()
        private var bufferedMs = 0
        private var started = false
        private var lastValidFrame: ByteArray? = null
        private var recoveryFrame: ByteArray? = null
        private var recoveryLeft = 0
        private var underruns = 0
        private var lastUnderrunAtNanos = 0L
        private var lastProvideNanos = 0L
        private var lastFillNs = 0L
        private var lastFillFrames = 0
        private var fillStarveStreak = 0
        private var otherGlobalBufferedMs = 0

        private val minPrerollMs = (serverConfig.minPrerollMs ?: DEFAULT_MIN_PREROLL_MS).coerceAtLeast(0)
        private var targetBufferMs = (serverConfig.dynamicTargetBufferMs ?: DEFAULT_TARGET_BUFFER_MS)
            .coerceIn(MIN_TARGET_BUFFER_MS, MAX_TARGET_BUFFER_MS)
        private val maxFrames = MAX_TARGET_BUFFER_MS / FRAME_DURATION_MS

        private val recoveryStrategy: RecoveryStrategy? = when (audioRecoveryConfig.strategy.lowercase().trim()) {
            "repeat" -> RepeatLastFrameRecovery()
            "noise" -> ComfortNoiseRecovery()
            "off" -> null
            else -> RepeatLastFrameRecovery()
        }

        private var lastUnderrunNanos = System.nanoTime()
        private var lastDecreaseNanos = lastUnderrunNanos

        init {
            if (targetBufferMs < minPrerollMs) {
                targetBufferMs = minPrerollMs
            }
        }

        fun onTrackStart() {
            clearBuffer()
            started = false
        }

        fun onTrackEnd() {
            clearBuffer()
        }

        fun onStuck() {
            increaseTarget(System.nanoTime())
        }

        private fun clearBuffer() {
            frameQueue.clear()
            bufferedMs = 0
            recoveryFrame = null
            recoveryLeft = 0
            lastValidFrame = null
            underruns = 0
            lastUnderrunAtNanos = 0L
            lastProvideNanos = 0L
            lastFillNs = 0L
            lastFillFrames = 0
            fillStarveStreak = 0
        }

        private fun increaseTarget(nowNanos: Long) {
            targetBufferMs = min(MAX_TARGET_BUFFER_MS, targetBufferMs + TARGET_STEP_MS)
            applyBufferCaps()
            lastUnderrunNanos = nowNanos
            if (lastDecreaseNanos < lastUnderrunNanos) {
                lastDecreaseNanos = lastUnderrunNanos
            }
        }

        private fun maybeDecreaseTarget(nowNanos: Long) {
            if (nowNanos - lastUnderrunNanos < NO_UNDERRUN_DECAY_NS) {
                return
            }
            if (nowNanos - lastDecreaseNanos < NO_UNDERRUN_DECAY_NS) {
                return
            }
            val floor = max(MIN_TARGET_BUFFER_MS, minPrerollMs)
            targetBufferMs = max(floor, targetBufferMs - TARGET_STEP_MS)
            applyBufferCaps()
            lastDecreaseNanos = nowNanos
        }

        private fun fillBuffer() {
            val start = System.nanoTime()
            var frames = 0
            var iterations = 0
            while (bufferedMs < targetBufferMs && iterations < MAX_FILL_ITERATIONS) {
                val cap = effectiveCapMs()
                if (cap != null && bufferedMs >= cap) {
                    break
                }
                val provided = audioPlayer.provide(mutableFrame)
                if (!provided) {
                    break
                }
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                buffer.clear()
                if (frameQueue.size >= maxFrames) {
                    break
                }
                frameQueue.add(bytes)
                bufferedMs += FRAME_DURATION_MS
                frames++
                iterations++
            }
            lastFillNs = System.nanoTime() - start
            lastFillFrames = frames
            if (frames == 0 && bufferedMs < minPrerollMs) {
                fillStarveStreak++
                if (fillStarveStreak >= 3) {
                    increaseTarget(System.nanoTime())
                    fillStarveStreak = 0
                }
            } else {
                fillStarveStreak = 0
            }
        }

        private fun applyBufferCaps() {
            val perPlayerCap = serverConfig.maxBufferPerPlayerMs
            val globalCap = serverConfig.maxGlobalBufferedMs
            var cap = Int.MAX_VALUE
            if (perPlayerCap != null && perPlayerCap > 0) {
                cap = min(cap, perPlayerCap)
            }
            if (globalCap != null && globalCap > 0) {
                cap = min(cap, max(0, globalCap - otherGlobalBufferedMs))
            }
            if (cap != Int.MAX_VALUE) {
                targetBufferMs = min(targetBufferMs, cap)
            }
        }

        private fun effectiveCapMs(): Int? {
            val perPlayerCap = serverConfig.maxBufferPerPlayerMs
            val globalCap = serverConfig.maxGlobalBufferedMs
            var cap = Int.MAX_VALUE
            var hasCap = false
            if (perPlayerCap != null && perPlayerCap > 0) {
                cap = min(cap, perPlayerCap)
                hasCap = true
            }
            if (globalCap != null && globalCap > 0) {
                cap = min(cap, max(0, globalCap - otherGlobalBufferedMs))
                hasCap = true
            }
            return if (hasCap) cap else null
        }

        override fun canProvide(): Boolean {
            otherGlobalBufferedMs = socketContext.getGlobalBufferedMs(guildId)
            fillBuffer()
            val nowNanos = System.nanoTime()
            maybeDecreaseTarget(nowNanos)
            maybePreloadNext()

            if (!started) {
                if (bufferedMs >= minPrerollMs) {
                    started = true
                } else {
                    return false
                }
            }

            if (frameQueue.isEmpty()) {
                log.warn("Opus buffer underrun for guild {}", guildId)
                underruns++
                lastUnderrunAtNanos = nowNanos
                increaseTarget(nowNanos)
                audioLossCounter.onLoss()
                if (recoveryLeft <= 0) {
                    recoveryFrame = recoveryStrategy?.recover(lastValidFrame)
                    recoveryLeft = if (recoveryFrame != null) RECOVERY_FRAMES else 0
                }
                return recoveryFrame != null
            }

            return true
        }

        override fun retrieveOpusFrame(buf: ByteBuf) {
            val recovery = recoveryFrame
            if (recovery != null && recoveryLeft > 0) {
                recoveryLeft--
                if (recoveryLeft == 0) {
                    recoveryFrame = null
                }
                buf.writeBytes(recovery)
                this@LavalinkPlayer.touchActive()
                return
            }
            val frame = frameQueue.pollFirst()
            if (frame == null) {
                audioLossCounter.onLoss()
                return
            }
            bufferedMs = max(0, bufferedMs - FRAME_DURATION_MS)
            audioLossCounter.onSuccess()
            lastValidFrame = frame
            lastProvideNanos = System.nanoTime()
            buf.writeBytes(frame)
            this@LavalinkPlayer.touchActive()
        }

        private fun maybePreloadNext() {
            val t = audioPlayer.playingTrack ?: return
            val dur = t.duration
            if (dur <= 0) return
            val left = dur - t.position
            if (left <= preloadAtMs) this@LavalinkPlayer.hintPreloadNext()
            if (left <= swapAtMs) this@LavalinkPlayer.trySwapNextSoon()
        }

        fun frameQueueSize() = frameQueue.size
        fun getBufferedMs() = bufferedMs
        fun getTargetBufferMs() = targetBufferMs
        fun getMinPrerollMs() = minPrerollMs
        fun getUnderruns() = underruns
        fun getLastFillNs() = lastFillNs
        fun getLastFillFrames() = lastFillFrames
        fun getRecoveryLeft() = recoveryLeft
        fun getLastUnderrunAtNanos() = lastUnderrunAtNanos

        companion object {
            private const val FRAME_DURATION_MS = 20
            private const val DEFAULT_MIN_PREROLL_MS = 300
            private const val DEFAULT_TARGET_BUFFER_MS = 600
            private const val MIN_TARGET_BUFFER_MS = 300
            private const val MAX_TARGET_BUFFER_MS = 1500
            private const val TARGET_STEP_MS = 100
            private const val MAX_FILL_ITERATIONS = 5
            private const val RECOVERY_FRAMES = 3
            private val NO_UNDERRUN_DECAY_NS = TimeUnit.SECONDS.toNanos(60)
        }
    }
}
