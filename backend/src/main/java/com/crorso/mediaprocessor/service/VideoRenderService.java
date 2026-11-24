package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.dto.RenderOptions;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/** Renders the still image as a looping H.264/AAC MP4 over the merged narration. */
@Service
public class VideoRenderService {

    private final CommandRunner runner;
    private final String ffmpegPath;

    public VideoRenderService(CommandRunner runner, MediaProperties properties) {
        this.runner = runner;
        this.ffmpegPath = properties.ffmpegPath();
    }

    public void render(Path image, Path narration, RenderOptions options, Path workDir, Path output) {
        CommandRunner.requireSuccess(
                runner.run(FfmpegCommands.renderStillVideo(ffmpegPath, image, narration, output, options), workDir),
                "rendering the video");
    }

    public void renderShort(Path image, Path narration, RenderOptions options, double startSeconds,
                            double durationSeconds, Path workDir, Path output) {
        CommandRunner.requireSuccess(
                runner.run(FfmpegCommands.renderShortClip(ffmpegPath, image, narration, output, options,
                        startSeconds, durationSeconds), workDir),
                "rendering the short clip");
    }
}
