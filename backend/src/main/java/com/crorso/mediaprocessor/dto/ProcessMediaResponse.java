package com.crorso.mediaprocessor.dto;

/**
 * Result of a successful {@code POST /api/v1/media/process}. Both download URLs point at files that
 * were verified before this response was produced. They require the same API key.
 */
public record ProcessMediaResponse(
        String storyId,
        String status,
        String message,
        String mergedAudioFilename,
        String videoFilename,
        long mergedAudioSizeBytes,
        long videoSizeBytes,
        double audioDurationSeconds,
        double videoDurationSeconds,
        long processingDurationMs,
        String audioDownloadUrl,
        String videoDownloadUrl,
        String requestId) {
}
