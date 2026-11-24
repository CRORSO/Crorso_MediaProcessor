package com.crorso.mediaprocessor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Validated video rendering options. Created by {@code RenderOptionsResolver} after defaults are applied.
 */
public record RenderOptions(
        @Min(value = 16, message = "must be at least 16") @Max(value = 7680, message = "must be at most 7680") int videoWidth,
        @Min(value = 16, message = "must be at least 16") @Max(value = 4320, message = "must be at most 4320") int videoHeight,
        @Min(value = 1, message = "must be at least 1") @Max(value = 60, message = "must be at most 60") int frameRate,
        @NotNull ImageFit imageFit,
        @NotNull @Pattern(regexp = "^[1-9][0-9]{1,2}k$", message = "must look like 128k, 192k or 320k") String audioBitrate,
        @NotNull @Pattern(regexp = "^(#[0-9A-Fa-f]{6}|[A-Za-z]{3,20})$", message = "must be a colour name or #RRGGBB") String backgroundColor,
        @NotNull OutputFormat outputFormat) {
}
