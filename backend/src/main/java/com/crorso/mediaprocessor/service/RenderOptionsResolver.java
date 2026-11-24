package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.dto.ImageFit;
import com.crorso.mediaprocessor.dto.OutputFormat;
import com.crorso.mediaprocessor.dto.RenderOptions;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** Applies configured defaults to optional request parameters and validates the result. */
@Component
public class RenderOptionsResolver {

    private final MediaProperties properties;
    private final Validator validator;

    public RenderOptionsResolver(MediaProperties properties, Validator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    public RenderOptions resolve(String outputFormat, Integer width, Integer height, Integer frameRate,
                                 String imageFit, String audioBitrate, String backgroundColor) {
        RenderOptions options = new RenderOptions(
                width != null ? width : properties.defaultVideoWidth(),
                height != null ? height : properties.defaultVideoHeight(),
                frameRate != null ? frameRate : properties.defaultFrameRate(),
                ImageFit.parse(imageFit),
                valueOrDefault(audioBitrate, properties.defaultAudioBitrate()),
                valueOrDefault(backgroundColor, properties.defaultBackgroundColor()),
                OutputFormat.parse(outputFormat));

        Set<ConstraintViolation<RenderOptions>> violations = validator.validate(options);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new MediaException(ErrorCode.INVALID_RENDER_OPTIONS, "Invalid rendering options: " + detail + ".");
        }
        if (options.videoWidth() % 2 != 0 || options.videoHeight() % 2 != 0) {
            throw new MediaException(ErrorCode.INVALID_RENDER_OPTIONS,
                    "videoWidth and videoHeight must be even numbers for H.264 yuv420p output.");
        }
        return options;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
