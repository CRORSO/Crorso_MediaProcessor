package com.crorso.mediaprocessor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings bound from the {@code media.*} namespace. Each value is populated from a
 * generic environment variable in {@code application.yml}; see {@code document/CONFIGURATION.md}.
 */
@Validated
@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        String apiKey,
        @NotBlank String tempDir,
        @NotBlank String outputDir,
        @NotBlank String ffmpegPath,
        @NotBlank String ffprobePath,
        @Positive int maxUploadSizeMb,
        @Positive int maxFileSizeMb,
        @Positive int maxAudioParts,
        @Positive int maxAudioDurationSeconds,
        @Positive int maxConcurrentJobs,
        @Positive int processingTimeoutSeconds,
        @Positive int outputRetentionHours,
        @Positive int defaultVideoWidth,
        @Positive int defaultVideoHeight,
        @Positive int defaultFrameRate,
        @NotBlank String defaultAudioBitrate,
        @NotBlank String defaultBackgroundColor,
        @Min(0) long minFreeDiskMb,
        String publicBaseUrl) {
}
