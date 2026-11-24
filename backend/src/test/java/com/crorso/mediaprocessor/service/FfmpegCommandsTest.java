package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.dto.ImageFit;
import com.crorso.mediaprocessor.dto.OutputFormat;
import com.crorso.mediaprocessor.dto.RenderOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegCommandsTest {

    private static final String FFMPEG = "ffmpeg";
    private static final Path IMAGE = Path.of("/work/input/image.png");
    private static final Path AUDIO = Path.of("/work/output/merged.mp3");
    private static final Path VIDEO = Path.of("/work/output/video.mp4");

    private static RenderOptions options(ImageFit fit, String color) {
        return new RenderOptions(1920, 1080, 24, fit, "192k", color, OutputFormat.MP4);
    }

    @Test
    void coverFilterFillsFrameAndCrops() {
        String filter = FfmpegCommands.frameFilter(options(ImageFit.COVER, "black"));
        assertThat(filter).isEqualTo(
                "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,setsar=1");
    }

    @Test
    void containFilterPadsWithConfiguredColour() {
        String filter = FfmpegCommands.frameFilter(options(ImageFit.CONTAIN, "#1A2B3C"));
        assertThat(filter).isEqualTo(
                "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x1A2B3C,setsar=1");
    }

    @Test
    void namedColoursPassThrough() {
        assertThat(FfmpegCommands.ffmpegColor("navy")).isEqualTo("navy");
    }

    @Test
    void videoCommandLoopsImageAndMapsNarration() {
        List<String> args = FfmpegCommands.renderStillVideo(FFMPEG, IMAGE, AUDIO, VIDEO, options(ImageFit.COVER, "black"));

        assertThat(args.get(0)).isEqualTo(FFMPEG);
        assertThat(args).containsSequence("-loop", "1", "-framerate", "24");
        assertThat(args).containsSequence("-map", "0:v:0", "-map", "1:a:0");
        assertThat(args).containsSequence("-c:v", "libx264");
        assertThat(args).containsSequence("-pix_fmt", "yuv420p");
        assertThat(args).containsSequence("-c:a", "aac", "-b:a", "192k");
        assertThat(args).contains("-shortest", "-movflags");
        assertThat(args).containsSequence("-movflags", "+faststart");
        assertThat(args.get(args.size() - 1)).isEqualTo(VIDEO.toAbsolutePath().toString());
    }

    @Test
    void joinFilterNormalisesEachInputThenConcatenatesInArgumentOrder() {
        String graph = FfmpegCommands.joinFilter(3);

        assertThat(graph).startsWith("[0:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a0];");
        assertThat(graph).contains("[2:a]aformat");
        assertThat(graph).endsWith("[a0][a1][a2]concat=n=3:v=0:a=1[out]");
    }

    @Test
    void singleCopyCommandDoesNotReencode() {
        List<String> copy = FfmpegCommands.copySingle(FFMPEG, Path.of("/work/input/part_001.mp3"), AUDIO);
        assertThat(copy).containsSequence("-c:a", "copy");
        assertThat(copy).doesNotContain("libmp3lame");
    }

    @Test
    void commandsAreArgumentListsWithoutShellInvocation() {
        List<String> join = FfmpegCommands.joinParts(FFMPEG, List.of(Path.of("/work/input/a.mp3")),
                Path.of("/work"), AUDIO, "128k");
        assertThat(join.get(0)).isEqualTo(FFMPEG);
        assertThat(join).doesNotContain("sh", "cmd", "/c", "-c");
    }
}
