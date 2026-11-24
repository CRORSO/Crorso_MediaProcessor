package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.service.storage.StoredMedia;

/**
 * Outcome of a successful run. Both outputs have been verified and published.
 */
public record ProcessingResult(
        String storyId,
        StoredMedia audio,
        StoredMedia video,
        double audioDurationSeconds,
        double videoDurationSeconds,
        long processingDurationMs) {
}
