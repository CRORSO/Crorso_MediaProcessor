package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.dto.RenderOptions;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import com.crorso.mediaprocessor.service.MediaInfo.StreamInfo;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Confirms generated files are playable and match what was requested before they are published.
 * Nothing is reported as successful unless these checks pass.
 */
@Component
public class OutputVerifier {

    private static final double MIN_TOLERANCE_SECONDS = 1.0;
    private static final double TOLERANCE_RATIO = 0.02;
    /** Allowance per joined part for decoder and encoder priming, which can differ by a few tens of ms. */
    private static final double TOLERANCE_PER_PART_SECONDS = 0.05;

    private final FfprobeService ffprobe;

    public OutputVerifier(FfprobeService ffprobe) {
        this.ffprobe = ffprobe;
    }

    /**
     * @param expectedSeconds sum of the input part durations
     * @param partCount       number of parts joined, used for the padding allowance
     * @return the measured duration of the merged audio
     */
    public double verifyAudio(Path mp3, double expectedSeconds, int partCount) throws IOException {
        requireNonEmpty(mp3);
        MediaInfo info = ffprobe.probe(mp3, ErrorCode.OUTPUT_VERIFICATION_FAILED, "The merged audio");
        StreamInfo audio = info.firstStream("audio").orElseThrow(OutputVerifier::verificationFailed);
        if (!info.formatName().toLowerCase(Locale.ROOT).contains("mp3") || !"mp3".equals(audio.codecName())) {
            throw verificationFailed();
        }
        requireDuration(info.durationSeconds(), expectedSeconds, partCount);
        return info.durationSeconds();
    }

    /** @return the measured duration of the rendered video */
    public double verifyVideo(Path mp4, double expectedSeconds, RenderOptions options) throws IOException {
        requireNonEmpty(mp4);
        MediaInfo info = ffprobe.probe(mp4, ErrorCode.OUTPUT_VERIFICATION_FAILED, "The rendered video");
        StreamInfo video = info.firstStream("video").orElseThrow(OutputVerifier::verificationFailed);
        StreamInfo audio = info.firstStream("audio").orElseThrow(OutputVerifier::verificationFailed);
        boolean dimensionsMatch = video.width() == options.videoWidth() && video.height() == options.videoHeight();
        if (!"h264".equals(video.codecName()) || !dimensionsMatch || !"aac".equals(audio.codecName())) {
            throw verificationFailed();
        }
        if (!info.formatName().contains("mp4") && !info.formatName().contains("mov")) {
            throw verificationFailed();
        }
        requireDuration(info.durationSeconds(), expectedSeconds, 0);
        return info.durationSeconds();
    }

    private static void requireDuration(double actual, double expected, int partCount) {
        double tolerance = Math.max(MIN_TOLERANCE_SECONDS, expected * TOLERANCE_RATIO)
                + partCount * TOLERANCE_PER_PART_SECONDS;
        if (actual <= 0 || Math.abs(actual - expected) > tolerance) {
            throw verificationFailed();
        }
    }

    private static void requireNonEmpty(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw verificationFailed();
        }
    }

    private static MediaException verificationFailed() {
        return new MediaException(ErrorCode.OUTPUT_VERIFICATION_FAILED, ErrorCode.OUTPUT_VERIFICATION_FAILED.defaultMessage());
    }
}
