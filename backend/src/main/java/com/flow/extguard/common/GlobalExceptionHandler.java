package com.flow.extguard.common;

import com.flow.extguard.config.StorageProperties;
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

    private final StorageProperties storageProperties;

    public GlobalExceptionHandler(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e) {
        return ResponseEntity.status(e.code().status()).body(ApiError.of(e));
    }

    /**
     * The servlet container aborts an over-limit upload before the controller is
     * reached. Without this handler the client would get an HTML error page
     * instead of the JSON shape it knows how to render.
     *
     * <p><strong>This is a verdict on the request, not on any one file.</strong>
     * Spring raises {@code MaxUploadSizeExceededException} for a part that is too
     * large <em>and</em> for a request with too many parts: Tomcat catches
     * {@code SizeException} and {@code FileCountLimitExceededException} in the
     * same block, and Spring's {@code handleParseFailure} converts anything whose
     * {@code toString()} carries "exceed" plus "size"/"count" into this one
     * exception. So which limit tripped is not recoverable here, and a message
     * naming only a size would be a guess -- one that reads as a per-file verdict
     * and, copied onto every row, told a user their 68KB file was too large.
     *
     * <p>The message therefore states both limits, and {@code ApiErrorCode}'s
     * size-only template is deliberately not used. The numbers come from
     * configuration rather than the literals in this class: the same pair the
     * upload screen shows and the service enforces.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e) {
        ApiErrorCode code = ApiErrorCode.FILE_TOO_LARGE;
        String message = "업로드 요청이 서버 한도를 초과했습니다. 한 번에 최대 %d개, 파일당 최대 %dMB까지 업로드할 수 있습니다."
                .formatted(storageProperties.getMaxFilesPerRequest(),
                        storageProperties.getMaxFileSize().toMegabytes());
        return ResponseEntity.status(code.status()).body(ApiError.of(
                code,
                message,
                "요청이 서버의 multipart 한도(파일당 크기 또는 파트 개수)를 초과해 "
                        + "파일별 검사 전에 거부됐습니다."));
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
