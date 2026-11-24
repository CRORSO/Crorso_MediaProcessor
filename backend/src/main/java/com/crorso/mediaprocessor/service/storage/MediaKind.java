package com.crorso.mediaprocessor.service.storage;

/**
 * The generated outputs per story. Filenames are deterministic from the story ID:
 * {@code <storyId>_full.mp3}, {@code <storyId>.mp4} and, when a vertical clip is requested, {@code <storyId>_short.mp4}.
 */
public enum MediaKind {

    AUDIO("_full.mp3", "audio/mpeg", "merged audio"),
    VIDEO(".mp4", "video/mp4", "video"),
    SHORT("_short.mp4", "video/mp4", "short clip");

    private final String suffix;
    private final String contentType;
    private final String label;

    MediaKind(String suffix, String contentType, String label) {
        this.suffix = suffix;
        this.contentType = contentType;
        this.label = label;
    }

    public String filename(String storyId) {
        return storyId + suffix;
    }

    public String contentType() {
        return contentType;
    }

    public String label() {
        return label;
    }
}
