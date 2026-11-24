package com.crorso.mediaprocessor.dto;

/**
 * Result of a successful {@code POST /api/v1/media/{storyId}/short}. The download URL points at a file that
 * was verified before this response was produced. It requires the same API key.
 */
public record ShortClipResponse(
        String storyId,
        String status,
        String message,
        String shortFilename,
        long shortSizeBytes,
        double startSeconds,
        double durationSeconds,
        long processingDurationMs,
        String shortDownloadUrl,
        String requestId) {
}
