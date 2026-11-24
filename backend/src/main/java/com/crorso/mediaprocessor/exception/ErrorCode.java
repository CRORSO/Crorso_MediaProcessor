package com.crorso.mediaprocessor.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, public error codes. The code name is returned to clients; the default message is safe to show.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "The request is missing required fields or is malformed."),
    INVALID_STORY_ID(HttpStatus.BAD_REQUEST, "storyId must be 1-64 characters: letters, digits, underscores or hyphens."),
    INVALID_AUDIO_PARTS(HttpStatus.BAD_REQUEST, "audioParts are invalid."),
    INVALID_RENDER_OPTIONS(HttpStatus.BAD_REQUEST, "One or more rendering options are invalid."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Missing or invalid API key."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    OUTPUT_NOT_FOUND(HttpStatus.NOT_FOUND, "No generated output exists for this story. Process the story again if it has expired."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported for this endpoint."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported."),
    STORY_BUSY(HttpStatus.CONFLICT, "A job for this story is already running. Retry after it completes."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The upload exceeds the configured size limits."),
    INVALID_IMAGE(HttpStatus.UNPROCESSABLE_ENTITY, "The image is not a supported PNG, JPEG, WEBP or BMP file."),
    INVALID_AUDIO(HttpStatus.UNPROCESSABLE_ENTITY, "An audio part is not a valid MP3 file."),
    AUDIO_TOO_LONG(HttpStatus.UNPROCESSABLE_ENTITY, "The combined narration is longer than the configured maximum."),
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Too many media jobs are running. Retry shortly."),
    FFMPEG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The media processing tools are not available on this server."),
    INSUFFICIENT_STORAGE(HttpStatus.INSUFFICIENT_STORAGE, "Not enough free disk space to process this story."),
    PROCESSING_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Media processing exceeded the time limit."),
    PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Media processing failed. Check the request inputs and retry."),
    OUTPUT_VERIFICATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "The generated media did not pass verification and was not saved."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
