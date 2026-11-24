package com.crorso.mediaprocessor.util;

import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioPartParserTest {

    private static final String STORY = "STORY-001";

    private static List<String> order(List<String> names, boolean allowGaps) {
        return AudioPartParser.order(names, n -> n, STORY, allowGaps, 200);
    }

    @Test
    void ordersPartsNumericallyNotLexicographically() {
        List<String> names = List.of(
                "STORY-001_part_010.mp3", "STORY-001_part_002.mp3", "STORY-001_part_001.mp3",
                "STORY-001_part_003.mp3", "STORY-001_part_004.mp3", "STORY-001_part_005.mp3",
                "STORY-001_part_006.mp3", "STORY-001_part_007.mp3", "STORY-001_part_008.mp3",
                "STORY-001_part_009.mp3");

        assertThat(order(names, false)).containsExactly(
                "STORY-001_part_001.mp3", "STORY-001_part_002.mp3", "STORY-001_part_003.mp3",
                "STORY-001_part_004.mp3", "STORY-001_part_005.mp3", "STORY-001_part_006.mp3",
                "STORY-001_part_007.mp3", "STORY-001_part_008.mp3", "STORY-001_part_009.mp3",
                "STORY-001_part_010.mp3");
    }

    @Test
    void ignoresMultipartSubmissionOrder() {
        List<String> names = List.of("STORY-001_part_002.mp3", "STORY-001_part_001.mp3");
        assertThat(order(names, false)).containsExactly("STORY-001_part_001.mp3", "STORY-001_part_002.mp3");
    }

    @Test
    void rejectsDuplicatePartNumbers() {
        List<String> names = List.of("STORY-001_part_001.mp3", "STORY-001_part_001.mp3");
        assertInvalid(() -> order(names, false), "Duplicate part number 1");
    }

    @Test
    void rejectsMissingSequenceNumbersUnlessGapsAllowed() {
        List<String> names = List.of("STORY-001_part_001.mp3", "STORY-001_part_003.mp3");
        assertInvalid(() -> order(names, false), "Missing part number 2");
        assertThat(order(names, true)).containsExactly("STORY-001_part_001.mp3", "STORY-001_part_003.mp3");
    }

    @Test
    void requiresNumberingToStartAtOne() {
        List<String> names = List.of("STORY-001_part_002.mp3");
        assertInvalid(() -> order(names, false), "Missing part number 1");
    }

    @Test
    void rejectsPartsBelongingToAnotherStory() {
        List<String> names = List.of("STORY-001_part_001.mp3", "STORY-999_part_002.mp3");
        assertInvalid(() -> order(names, false), "does not belong to storyId");
    }

    @Test
    void rejectsNamesThatDoNotMatchPattern() {
        assertInvalid(() -> order(List.of("narration.mp3"), false), "must match");
        assertInvalid(() -> order(List.of("STORY-001_part_001.wav"), false), "must match");
    }

    @Test
    void stripsDirectoryComponentsBeforeParsing() {
        List<String> names = List.of("C:\\uploads\\STORY-001_part_001.mp3", "../other/STORY-001_part_002.mp3");
        assertThat(order(names, false)).hasSize(2);
    }

    @Test
    void rejectsEmptyAndOversizedLists() {
        assertInvalid(() -> order(List.of(), false), "At least one");
        assertInvalid(() -> AudioPartParser.order(List.of("a", "b", "c"), n -> n, STORY, false, 2), "Too many");
    }

    @Test
    void acceptsUppercaseExtension() {
        assertThat(order(List.of("STORY-001_part_001.MP3"), false)).hasSize(1);
    }

    private static void assertInvalid(Runnable action, String messageFragment) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MediaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_AUDIO_PARTS);
                    assertThat(e.getMessage()).contains(messageFragment);
                });
    }
}
