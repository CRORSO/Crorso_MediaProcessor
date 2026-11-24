package com.crorso.mediaprocessor.service;

/** A narration chunk that has been validated and stored for later processing. */
public record StagedPart(String storyId, int partNumber, String filename, long sizeBytes, double durationSeconds) {
}
