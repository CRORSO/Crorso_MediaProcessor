package com.crorso.mediaprocessor.dto;

import com.crorso.mediaprocessor.exception.ErrorCode;

import java.time.Instant;

/**
 * Error body returned for every failed request.
 *
 * @param timestamp when the error was produced
 * @param status    HTTP status code
 * @param code      stable error code, e.g. {@code INVALID_STORY_ID}
 * @param message   safe, user-facing description
 * @param requestId correlation ID also present in server logs and the {@code X-Request-Id} header
 */
public record ErrorResponse(Instant timestamp, int status, String code, String message, String requestId) {

    public static ErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ErrorResponse(Instant.now(), code.status().value(), code.name(), message, requestId);
    }
}
