package com.crorso.mediaprocessor.service.storage;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import com.crorso.mediaprocessor.util.StoryIdValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stores workspaces under {@code MEDIA_TEMP_DIR} and outputs under {@code MEDIA_OUTPUT_DIR/<storyId>/}.
 *
 * <p>Every path is derived from a validated story ID, normalised and checked to stay inside its root.
 * Symbolic links are never followed when deleting or publishing. Outputs are published by copying to a
 * hidden staging file in the same directory and then doing an atomic rename over the target.
 */
@Component
public class LocalMediaStorage implements MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalMediaStorage.class);
    static final String STAGING_DIR = "staged";

    private final Path tempRoot;
    private final Path outputRoot;

    public LocalMediaStorage(MediaProperties properties) {
        this.tempRoot = Path.of(properties.tempDir()).toAbsolutePath().normalize();
        this.outputRoot = Path.of(properties.outputDir()).toAbsolutePath().normalize();
    }

    /**
     * Creates both roots and removes workspaces left behind by an earlier crash. Refuses to run when the
     * temp root would contain the output root, because the cleanup would then delete published outputs.
     */
    @PostConstruct
    void initialise() throws IOException {
        if (tempRoot.getParent() == null || outputRoot.getParent() == null) {
            throw new IllegalStateException("MEDIA_TEMP_DIR and MEDIA_OUTPUT_DIR must not be filesystem roots");
        }
        if (outputRoot.startsWith(tempRoot)) {
            throw new IllegalStateException("MEDIA_OUTPUT_DIR must not be inside MEDIA_TEMP_DIR");
        }
        Files.createDirectories(tempRoot);
        Files.createDirectories(outputRoot);
        try (Stream<Path> stale = Files.list(tempRoot)) {
            stale.forEach(this::deleteWorkspace);
        }
    }

    @Override
    public Path createWorkspace() throws IOException {
        Path workspace = tempRoot.resolve("job-" + UUID.randomUUID()).normalize();
        requireInside(tempRoot, workspace);
        return Files.createDirectories(workspace);
    }

    @Override
    public void deleteWorkspace(Path workspace) {
        if (workspace == null || !workspace.normalize().startsWith(tempRoot) || workspace.equals(tempRoot)) {
            log.warn("Refused to delete a path outside the temp directory");
            return;
        }
        deleteRecursively(workspace);
    }

    @Override
    public StoredMedia publish(String storyId, MediaKind kind, Path verifiedFile) throws IOException {
        Path storyDir = storyDirectory(storyId, true);
        String filename = kind.filename(storyId);
        Path target = storyDir.resolve(filename);
        Path staging = storyDir.resolve("." + filename + "." + UUID.randomUUID() + ".tmp");

        try {
            Files.copy(verifiedFile, staging, StandardCopyOption.COPY_ATTRIBUTES);
            moveAtomically(staging, target);
        } finally {
            Files.deleteIfExists(staging);
        }
        return requireStored(target, kind);
    }

    @Override
    public Optional<StoredMedia> find(String storyId, MediaKind kind) {
        Path storyDir = storyDirectory(storyId, false);
        if (storyDir == null) {
            return Optional.empty();
        }
        Path target = storyDir.resolve(kind.filename(storyId));
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(requireStored(target, kind));
        } catch (IOException e) {
            log.warn("Output for story {} could not be read: {}", storyId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public boolean deleteStory(String storyId) {
        Path storyDir = storyDirectory(storyId, false);
        if (storyDir == null) {
            return false;
        }
        deleteRecursively(storyDir);
        log.info("Deleted outputs for story {}", storyId);
        return true;
    }

    @Override
    public int deleteExpired(Instant cutoff) {
        int removed = 0;
        try (Stream<Path> children = Files.list(outputRoot)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Instant newest = newestModified(child);
                if (newest != null && newest.isBefore(cutoff)) {
                    deleteRecursively(child);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Retention sweep could not list the output directory: {}", e.getClass().getSimpleName());
        }
        return removed;
    }

    @Override
    public Path stagingDirectory(String storyId) {
        Path dir = storyDirectory(storyId, true).resolve(STAGING_DIR);
        try {
            if (Files.isSymbolicLink(dir)) {
                throw new MediaException(ErrorCode.PROCESSING_FAILED, "The staging directory could not be used.");
            }
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MediaException(ErrorCode.PROCESSING_FAILED, "The staging directory could not be created.", e);
        }
    }

    @Override
    public void clearStagedParts(String storyId) {
        Path storyDir = storyDirectory(storyId, false);
        if (storyDir != null) {
            deleteRecursively(storyDir.resolve(STAGING_DIR));
        }
    }

    @Override
    public long usableBytes() {
        try {
            long temp = Files.getFileStore(tempRoot).getUsableSpace();
            long output = Files.getFileStore(outputRoot).getUsableSpace();
            return Math.min(temp, output);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Resolves a story's output directory. Returns null when {@code create} is false and the directory
     * does not exist. Throws when the resolved path escapes the output root or is a symbolic link.
     */
    private Path storyDirectory(String storyId, boolean create) {
        Path storyDir = outputRoot.resolve(StoryIdValidator.validate(storyId)).normalize();
        requireInside(outputRoot, storyDir);
        try {
            if (Files.isSymbolicLink(storyDir)) {
                throw new MediaException(ErrorCode.INVALID_STORY_ID, ErrorCode.INVALID_STORY_ID.defaultMessage());
            }
            if (!Files.exists(storyDir, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) {
                    return null;
                }
                Files.createDirectories(storyDir);
            }
            requireInside(outputRoot.toRealPath(), storyDir.toRealPath());
            return storyDir;
        } catch (IOException e) {
            throw new MediaException(ErrorCode.PROCESSING_FAILED, "The output directory could not be accessed.", e);
        }
    }

    private StoredMedia requireStored(Path target, MediaKind kind) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target)) {
            throw new IOException("Output is not a regular file");
        }
        long size = Files.size(target);
        if (size <= 0) {
            throw new IOException("Output is empty");
        }
        return new StoredMedia(target, target.getFileName().toString(), kind.contentType(), size);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Instant newestModified(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files
                    .filter(p -> !p.equals(directory))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(LocalMediaStorage::lastModified)
                    .max(Instant::compareTo)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException e) {
            return Instant.MIN;
        }
    }

    /** Deletes a tree without following symbolic links. Failures are logged and do not propagate. */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not fully delete a media directory: {}", e.getClass().getSimpleName());
        }
    }

    private static void requireInside(Path root, Path candidate) {
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new MediaException(ErrorCode.INVALID_STORY_ID, ErrorCode.INVALID_STORY_ID.defaultMessage());
        }
    }
}
