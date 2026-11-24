package com.crorso.mediaprocessor.dto;

/** Result of staging one narration part. */
public record StagedPartResponse(
        String storyId,
        int partNumber,
        String filename,
        long sizeBytes,
        double durationSeconds,
        String status,
        String requestId) {
}
