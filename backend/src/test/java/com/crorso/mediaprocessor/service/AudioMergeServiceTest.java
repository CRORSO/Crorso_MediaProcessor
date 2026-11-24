package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.service.MediaInfo.StreamInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudioMergeServiceTest {

    private static MediaInfo info(int sampleRate, int channels) {
        return new MediaInfo("mp3", 1.0, List.of(new StreamInfo("audio", "mp3", sampleRate, channels, 0, 0)));
    }

    @Test
    void targetFormatPartNeedsNoNormalisation() {
        assertThat(AudioMergeService.needsNormalization(info(44_100, 2))).isFalse();
    }

    @Test
    void otherSampleRatesOrChannelCountsNeedNormalisation() {
        assertThat(AudioMergeService.needsNormalization(info(22_050, 2))).isTrue();
        assertThat(AudioMergeService.needsNormalization(info(44_100, 1))).isTrue();
    }

    @Test
    void joinCommandReadsInputsRelativeToWorkspace() {
        List<Path> parts = List.of(Path.of("/work/input/part_001.mp3"), Path.of("/work/input/part_002.mp3"));

        List<String> command = FfmpegCommands.joinParts("ffmpeg", parts, Path.of("/work"), Path.of("/work/output/merged.mp3"), "192k");

        assertThat(command).containsSequence("-i", "input/part_001.mp3", "-i", "input/part_002.mp3");
        assertThat(command).containsSequence("-c:a", "libmp3lame", "-b:a", "192k");
        assertThat(command).doesNotContainSequence("-c:a", "copy");
    }
}
