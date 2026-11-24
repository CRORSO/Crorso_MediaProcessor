package com.crorso.mediaprocessor.exception;

import com.crorso.mediaprocessor.dto.ErrorResponse;
import com.crorso.mediaprocessor.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MediaException.class)
    ResponseEntity<ErrorResponse> handleMediaException(MediaException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.currentRequestId(request);
        if (ex.errorCode().status().is5xxServerError()) {
            log.error("Request {} failed with {}: {}", requestId, ex.errorCode(), ex.getMessage(), ex.getCause());
        } else {
            log.info("Request {} rejected with {}: {}", requestId, ex.errorCode(), ex.getMessage());
        }
        return respond(ex.errorCode(), ex.getMessage(), requestId);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleUploadTooLarge(HttpServletRequest request) {
        return respond(ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.defaultMessage(), RequestIdFilter.currentRequestId(request));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MultipartException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.currentRequestId(request);
        log.info("Request {} is malformed: {}", requestId, ex.getClass().getSimpleName());
        return respond(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), requestId);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(), RequestIdFilter.currentRequestId(request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), RequestIdFilter.currentRequestId(request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), RequestIdFilter.currentRequestId(request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.currentRequestId(request);
        log.error("Request {} failed with an unexpected error", requestId, ex);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), requestId);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, String requestId) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message, requestId));
    }
}
