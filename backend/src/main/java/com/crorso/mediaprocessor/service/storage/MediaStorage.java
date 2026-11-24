package com.crorso.mediaprocessor.service.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Storage boundary for the processing pipeline. The local-disk implementation is the only one today.
 * A persistent object-storage implementation can replace it without changing the pipeline:
 * {@link #publish} would upload, {@link #find} would return a signed or streamed location.
 */
public interface MediaStorage {

    /** Creates an isolated, empty working directory that no other job can see. */
    Path createWorkspace() throws IOException;

    /** Removes a workspace and everything in it. Never throws; cleanup failures are logged. */
    void deleteWorkspace(Path workspace);

    /**
     * Atomically installs a verified file as the story's output, replacing any previous one.
     * Readers see either the old file or the new one, never a partial write.
     */
    StoredMedia publish(String storyId, MediaKind kind, Path verifiedFile) throws IOException;

    Optional<StoredMedia> find(String storyId, MediaKind kind);

    /** Deletes all outputs for a story. Returns false when the story has no outputs. */
    boolean deleteStory(String storyId);

    /** Deletes stories whose newest output is older than the cutoff. Returns the number removed. */
    int deleteExpired(Instant cutoff);

    /** Directory holding parts staged for a story, created on demand. */
    Path stagingDirectory(String storyId);

    /** Removes staged parts for a story. Published outputs are untouched. */
    void clearStagedParts(String storyId);

    /** Smallest usable byte count across the temp and output volumes. */
    long usableBytes();
}
