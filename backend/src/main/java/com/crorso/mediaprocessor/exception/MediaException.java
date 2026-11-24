package com.crorso.mediaprocessor.exception;

/**
 * Domain failure with a public error code. The message must be safe to return to API callers:
 * no filesystem paths, command lines or raw FFmpeg output.
 */
public class MediaException extends RuntimeException {

    private final ErrorCode errorCode;

    public MediaException(ErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, null);
    }

    public MediaException(ErrorCode errorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
