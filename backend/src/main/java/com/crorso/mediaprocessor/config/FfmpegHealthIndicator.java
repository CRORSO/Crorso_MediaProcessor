package com.crorso.mediaprocessor.config;

import com.crorso.mediaprocessor.service.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Reports DOWN when FFmpeg or FFprobe cannot be executed, so a broken image fails its Railway
 * health check instead of accepting jobs it cannot run. The result is cached for one minute.
 */
@Component("ffmpeg")
public class FfmpegHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(FfmpegHealthIndicator.class);
    private static final long CACHE_MILLIS = Duration.ofMinutes(1).toMillis();

    private final CommandRunner runner;
    private final MediaProperties properties;
    private Health cached;
    private long checkedAtMillis;

    public FfmpegHealthIndicator(CommandRunner runner, MediaProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @Override
    public synchronized Health health() {
        long now = System.currentTimeMillis();
        if (cached != null && now - checkedAtMillis < CACHE_MILLIS) {
            return cached;
        }
        cached = probeTools();
        checkedAtMillis = now;
        return cached;
    }

    private Health probeTools() {
        Path workDir = Path.of(properties.tempDir());
        try {
            CommandRunner.Result ffmpeg = runner.run(List.of(properties.ffmpegPath(), "-version"), workDir, Duration.ofSeconds(10));
            CommandRunner.Result ffprobe = runner.run(List.of(properties.ffprobePath(), "-version"), workDir, Duration.ofSeconds(10));
            if (ffmpeg.success() && ffprobe.success()) {
                return Health.up().build();
            }
            log.error("FFmpeg or FFprobe returned a non-zero exit code during the health check");
        } catch (RuntimeException e) {
            log.error("FFmpeg or FFprobe is not available: {}", e.getMessage());
        }
        return Health.down().build();
    }
}
