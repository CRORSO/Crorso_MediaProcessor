package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.dto.ImageFit;
import com.crorso.mediaprocessor.dto.RenderOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds FFmpeg argument lists. Pure functions with no I/O, so argument construction can be unit tested.
 * Every value passed here has already been validated by the caller.
 */
public final class FfmpegCommands {

    static final int TARGET_SAMPLE_RATE = 44_100;
    static final int TARGET_CHANNELS = 2;

    private static final List<String> QUIET = List.of("-hide_banner", "-nostdin", "-y", "-loglevel", "error");

    private FfmpegCommands() {
    }

    /** Remuxes a single MP3 without re-encoding. */
    public static List<String> copySingle(String ffmpeg, Path input, Path output) {
        List<String> command = base(ffmpeg);
        command.addAll(List.of("-i", path(input), "-vn", "-map", "0:a:0", "-c:a", "copy",
                "-map_metadata", "-1", "-f", "mp3", path(output)));
        return command;
    }

    /**
     * Joins ordered parts in one decode-and-encode pass. Each input is converted to the target format inside
     * the filter graph, so mixed sample rates and channel counts are handled without a separate step, and
     * encoder padding is produced once instead of at every boundary.
     *
     * <p>Inputs are passed as paths relative to {@code workDir}, and the process runs in that directory,
     * which keeps the command line short for large part counts.
     */
    public static List<String> joinParts(String ffmpeg, List<Path> inputs, Path workDir, Path output, String bitrate) {
        List<String> command = base(ffmpeg);
        for (Path input : inputs) {
            command.addAll(List.of("-i", relative(workDir, input)));
        }
        command.addAll(List.of(
                "-filter_complex", joinFilter(inputs.size()),
                "-map", "[out]",
                "-c:a", "libmp3lame", "-b:a", bitrate,
                "-ar", String.valueOf(TARGET_SAMPLE_RATE), "-ac", String.valueOf(TARGET_CHANNELS),
                "-map_metadata", "-1", "-f", "mp3", path(output)));
        return command;
    }

    /** Builds the filter graph that normalises each input and concatenates them in argument order. */
    static String joinFilter(int count) {
        StringBuilder graph = new StringBuilder();
        for (int i = 0; i < count; i++) {
            graph.append(String.format(Locale.ROOT,
                    "[%d:a]aformat=sample_fmts=fltp:sample_rates=%d:channel_layouts=stereo[a%d];",
                    i, TARGET_SAMPLE_RATE, i));
        }
        for (int i = 0; i < count; i++) {
            graph.append("[a").append(i).append(']');
        }
        graph.append("concat=n=").append(count).append(":v=0:a=1[out]");
        return graph.toString();
    }

    /**
     * Renders a looping still image over the full narration. {@code -shortest} ends the output
     * when the audio ends, so the video length always matches the narration.
     */
    public static List<String> renderStillVideo(String ffmpeg, Path image, Path audio, Path output,
                                                RenderOptions options) {
        List<String> command = base(ffmpeg);
        String fps = String.valueOf(options.frameRate());
        command.addAll(List.of("-loop", "1", "-framerate", fps, "-i", path(image)));
        command.addAll(List.of("-i", path(audio)));
        command.addAll(List.of(
                "-map", "0:v:0", "-map", "1:a:0",
                "-vf", frameFilter(options),
                "-c:v", "libx264", "-preset", "medium", "-tune", "stillimage", "-crf", "20",
                "-pix_fmt", "yuv420p", "-r", fps,
                "-c:a", "aac", "-b:a", options.audioBitrate(),
                "-shortest",
                "-movflags", "+faststart",
                "-f", "mp4", path(output)));
        return command;
    }

    /** Builds the video filter for the requested fit mode. Exposed for tests. */
    public static String frameFilter(RenderOptions options) {
        int w = options.videoWidth();
        int h = options.videoHeight();
        if (options.imageFit() == ImageFit.CONTAIN) {
            return String.format(Locale.ROOT,
                    "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=%s,setsar=1",
                    w, h, w, h, ffmpegColor(options.backgroundColor()));
        }
        return String.format(Locale.ROOT,
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1", w, h, w, h);
    }

    /** Converts {@code #RRGGBB} to FFmpeg's {@code 0xRRGGBB} form; colour names pass through. */
    static String ffmpegColor(String color) {
        return color.startsWith("#") ? "0x" + color.substring(1) : color;
    }

    private static List<String> base(String ffmpeg) {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.addAll(QUIET);
        return command;
    }

    private static String path(Path path) {
        return path.toAbsolutePath().toString();
    }

    private static String relative(Path base, Path path) {
        return base.toAbsolutePath().relativize(path.toAbsolutePath()).toString().replace('\\', '/');
    }
}
