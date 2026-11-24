package com.crorso.mediaprocessor.util;

import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryIdAndFilenameTest {

    @ParameterizedTest
    @ValueSource(strings = {"STORY-001", "story_42", "A", "tamil-story-2026-10-24"})
    void acceptsPermittedStoryIds(String id) {
        assertThat(StoryIdValidator.validate(id)).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", "../etc", "a/b", "a\\b", "a.b", "-leading", "_leading", "space here"})
    void rejectsUnsafeStoryIds(String id) {
        assertThatThrownBy(() -> StoryIdValidator.validate(id))
                .isInstanceOfSatisfying(MediaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STORY_ID));
    }

    @Test
    void rejectsOverlongStoryId() {
        assertThatThrownBy(() -> StoryIdValidator.validate("a".repeat(65))).isInstanceOf(MediaException.class);
    }

    @Test
    void rejectsNullStoryId() {
        assertThatThrownBy(() -> StoryIdValidator.validate(null)).isInstanceOf(MediaException.class);
    }

    @Test
    void sanitizesFilenamesToSafeBasenames() {
        assertThat(FilenameSanitizer.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FilenameSanitizer.sanitize("C:\\temp\\my audio (1).mp3")).isEqualTo("my_audio__1_.mp3");
        assertThat(FilenameSanitizer.sanitize("..")).isEqualTo("unnamed");
        assertThat(FilenameSanitizer.sanitize(null)).isEqualTo("unnamed");
        assertThat(FilenameSanitizer.sanitize("x".repeat(300) + ".mp3")).hasSize(120).endsWith(".mp3");
    }
}
