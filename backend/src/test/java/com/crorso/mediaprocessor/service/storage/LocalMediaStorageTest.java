package com.crorso.mediaprocessor.service.storage;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMediaStorageTest {

    @TempDir
    Path root;

    private Path tempDir;
    private Path outputDir;
    private LocalMediaStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = root.resolve("temp");
        outputDir = root.resolve("output");
        storage = new LocalMediaStorage(new MediaProperties(null, tempDir.toString(), outputDir.toString(),
                "ffmpeg", "ffprobe", 500, 25, 200, 7200, 2, 900, 24, 1920, 1080, 24, "192k", "black", 0, null));
        storage.initialise();
    }

    @Test
    void publishedOutputCanBeFoundWithMetadata() throws IOException {
        Path verified = writeFile(root.resolve("verified.mp3"), "audio-bytes");

        StoredMedia published = storage.publish("STORY-1", MediaKind.AUDIO, verified);

        assertThat(published.filename()).isEqualTo("STORY-1_full.mp3");
        assertThat(published.contentType()).isEqualTo("audio/mpeg");
        assertThat(published.sizeBytes()).isEqualTo("audio-bytes".length());
        assertThat(storage.find("STORY-1", MediaKind.AUDIO)).map(StoredMedia::sizeBytes)
                .contains((long) "audio-bytes".length());
    }

    @Test
    void republishReplacesPreviousOutputWithoutLeavingStagingFiles() throws IOException {
        storage.publish("STORY-1", MediaKind.VIDEO, writeFile(root.resolve("v1.mp4"), "first-version"));
        storage.publish("STORY-1", MediaKind.VIDEO, writeFile(root.resolve("v2.mp4"), "second"));

        Path storyDir = outputDir.resolve("STORY-1");
        assertThat(Files.readString(storyDir.resolve("STORY-1.mp4"))).isEqualTo("second");
        try (var files = Files.list(storyDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("STORY-1.mp4");
        }
    }

    @Test
    void missingOutputIsEmpty() {
        assertThat(storage.find("NOPE", MediaKind.AUDIO)).isEqualTo(Optional.empty());
    }

    @Test
    void deleteStoryRemovesOutputsAndReportsWhetherAnythingExisted() throws IOException {
        storage.publish("STORY-1", MediaKind.AUDIO, writeFile(root.resolve("a.mp3"), "x"));

        assertThat(storage.deleteStory("STORY-1")).isTrue();
        assertThat(storage.find("STORY-1", MediaKind.AUDIO)).isEmpty();
        assertThat(storage.deleteStory("STORY-1")).isFalse();
    }

    @Test
    void traversalAttemptsNeverLeaveTheOutputDirectory() throws IOException {
        Path victim = writeFile(root.resolve("victim.mp3"), "keep me");

        assertThatThrownBy(() -> storage.publish("../escape", MediaKind.AUDIO, victim))
                .isInstanceOfSatisfying(MediaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STORY_ID));
        assertThatThrownBy(() -> storage.deleteStory("..")).isInstanceOf(MediaException.class);
        assertThat(Files.readString(victim)).isEqualTo("keep me");
        assertThat(Files.exists(root.resolve("escape"))).isFalse();
    }

    @Test
    void symbolicLinkedStoryDirectoryIsRejected() throws IOException {
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("secret.mp3"), "secret");
        try {
            Files.createSymbolicLink(outputDir.resolve("LINKED"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "Symbolic links are not permitted on this system");
        }

        assertThatThrownBy(() -> storage.find("LINKED", MediaKind.AUDIO)).isInstanceOf(MediaException.class);
        assertThatThrownBy(() -> storage.deleteStory("LINKED")).isInstanceOf(MediaException.class);
        assertThat(Files.exists(outside.resolve("secret.mp3"))).isTrue();
    }

    @Test
    void retentionRemovesOnlyStoriesOlderThanCutoff() throws IOException {
        storage.publish("OLD-STORY", MediaKind.AUDIO, writeFile(root.resolve("old.mp3"), "old"));
        storage.publish("NEW-STORY", MediaKind.AUDIO, writeFile(root.resolve("new.mp3"), "new"));
        Files.setLastModifiedTime(outputDir.resolve("OLD-STORY/OLD-STORY_full.mp3"),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(48))));

        int removed = storage.deleteExpired(Instant.now().minus(Duration.ofHours(24)));

        assertThat(removed).isEqualTo(1);
        assertThat(storage.find("OLD-STORY", MediaKind.AUDIO)).isEmpty();
        assertThat(storage.find("NEW-STORY", MediaKind.AUDIO)).isPresent();
    }

    @Test
    void workspacesAreIsolatedAndDeletedWithoutTouchingOutsideFiles() throws IOException {
        Path first = storage.createWorkspace();
        Path second = storage.createWorkspace();
        assertThat(first).isNotEqualTo(second);
        writeFile(first.resolve("input.mp3"), "data");

        storage.deleteWorkspace(first);
        assertThat(Files.exists(first)).isFalse();
        assertThat(Files.exists(second)).isTrue();

        Path outside = writeFile(root.resolve("outside.txt"), "keep");
        storage.deleteWorkspace(outside.getParent());
        assertThat(Files.exists(outside)).isTrue();
    }

    @Test
    void refusesToStartWhenOutputWouldBeInsideTempDirectory() {
        LocalMediaStorage unsafe = new LocalMediaStorage(new MediaProperties(null, root.toString(),
                root.resolve("nested-output").toString(), "ffmpeg", "ffprobe", 500, 25, 200, 7200, 2, 900, 24,
                1920, 1080, 24, "192k", "black", 0, null));
        assertThatThrownBy(unsafe::initialise).isInstanceOf(IllegalStateException.class);
    }

    private static Path writeFile(Path path, String content) throws IOException {
        return Files.writeString(path, content);
    }
}
