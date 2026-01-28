package lavalink.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "audio.recovery")
@Component
class AudioRecoveryConfig {
    var strategy: String = "repeat"
}
