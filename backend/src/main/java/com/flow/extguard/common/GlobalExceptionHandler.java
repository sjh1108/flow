package com.flow.extguard.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps every failure onto the one {@link ApiError} shape, so the frontend has a
 * single thing to parse and can always show a Korean message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e) {
        return ResponseEntity.status(e.code().status()).body(ApiError.of(e));
    }

    /**
     * The servlet container aborts an oversized upload before the controller is
     * reached. Without this handler the client would get an HTML error page
     * instead of the JSON shape it knows how to render.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e) {
        ApiErrorCode code = ApiErrorCode.FILE_TOO_LARGE;
        return ResponseEntity.status(code.status()).body(ApiError.of(
                code,
                code.message("설정된 최대 크기"),
                "요청이 서버의 multipart 크기 제한을 초과했습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("요청 본문이 올바르지 않습니다.");
        ApiErrorCode code = ApiErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(ApiError.of(code, code.message(), detail));
    }

    /** A missing query or form parameter is the caller's mistake, not a server fault. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException e) {
        ApiErrorCode code = ApiErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(ApiError.of(
                code, code.message(), "필수 파라미터 '" + e.getParameterName() + "'가 없습니다."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        ApiErrorCode code = ApiErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, code.message(), "요청 본문을 해석할 수 없습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // Spring MVC reports ordinary client mistakes -- an unsupported method, an
        // unknown path, a wrong content type -- as exceptions that already carry
        // their own 4xx status. Letting them fall through to 500 would both
        // mislead the caller and bury genuine server faults among them in the log.
        if (e instanceof ErrorResponse errorResponse
                && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = errorResponse.getStatusCode();
            ApiErrorCode code = clientErrorCode(status);
            log.warn("Client error {}: {}", status.value(), e.getMessage());
            return ResponseEntity.status(status)
                    .body(ApiError.of(code, code.message(), e.getMessage()));
        }

        // Logged in full here; the response deliberately carries no internal detail.
        log.error("Unhandled exception", e);
        ApiErrorCode code = ApiErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status()).body(ApiError.of(code, code.message(), null));
    }

    private static ApiErrorCode clientErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ApiErrorCode.ENDPOINT_NOT_FOUND;
            case 405 -> ApiErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ApiErrorCode.BAD_REQUEST;
        };
    }
}
