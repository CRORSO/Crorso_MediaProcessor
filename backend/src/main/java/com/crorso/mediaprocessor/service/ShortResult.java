package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.service.storage.StoredMedia;

/** Outcome of a successful clip render. The clip has been verified and published. */
public record ShortResult(
        String storyId,
        StoredMedia clip,
        double startSeconds,
        double durationSeconds,
        long processingDurationMs) {
}
