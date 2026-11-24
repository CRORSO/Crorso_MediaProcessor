package com.crorso.mediaprocessor.service.storage;

import com.crorso.mediaprocessor.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Periodically removes outputs older than {@code OUTPUT_RETENTION_HOURS}. */
@Component
public class OutputRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutputRetentionScheduler.class);

    private final MediaStorage storage;
    private final Duration retention;

    public OutputRetentionScheduler(MediaStorage storage, MediaProperties properties) {
        this.storage = storage;
        this.retention = Duration.ofHours(properties.outputRetentionHours());
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void purgeExpiredOutputs() {
        int removed = storage.deleteExpired(Instant.now().minus(retention));
        if (removed > 0) {
            log.info("Retention sweep removed {} expired stories", removed);
        }
    }
}
