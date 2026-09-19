package com.flow.extguard.common;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API can report, with the HTTP status and the Korean message
 * shown to the user.
 *
 * <p>Requirement B asks for blocked uploads to be refused "with a clear reason".
 * The message here is the headline the UI renders; callers supply a {@code detail}
 * string alongside it carrying the specific evidence (which filename, which
 * extension segment, which signature).
 */
public enum ApiErrorCode {

    // --- extension input validation -----------------------------------------
    EXT_EMPTY(HttpStatus.BAD_REQUEST, "확장자를 입력해 주세요."),
    EXT_TOO_LONG(HttpStatus.BAD_REQUEST, "확장자는 최대 %d자까지 입력할 수 있습니다."),
    EXT_CONTAINS_DOT(HttpStatus.BAD_REQUEST, "확장자에는 점(.)을 포함할 수 없습니다. 점 없이 입력해 주세요."),
    EXT_CONTAINS_WHITESPACE(HttpStatus.BAD_REQUEST, "확장자에는 공백을 포함할 수 없습니다."),
    EXT_INVALID_CHARSET(HttpStatus.BAD_REQUEST, "확장자는 영문자와 숫자만 사용할 수 있습니다."),

    // --- policy -------------------------------------------------------------
    EXT_IS_FIXED(HttpStatus.CONFLICT,
            "'%s'는 고정 확장자입니다. 위 고정 확장자 영역에서 체크해 주세요."),
    EXT_DUPLICATE(HttpStatus.CONFLICT, "'%s'는 이미 추가된 확장자입니다."),
    EXT_NOT_FOUND(HttpStatus.NOT_FOUND, "'%s'는 등록되어 있지 않은 커스텀 확장자입니다."),
    FIXED_EXTENSION_UNKNOWN(HttpStatus.NOT_FOUND, "'%s'는 고정 확장자가 아닙니다."),
    CUSTOM_LIMIT_EXCEEDED(HttpStatus.CONFLICT,
            "커스텀 확장자는 최대 %d개까지 추가할 수 있습니다. 사용하지 않는 항목을 삭제한 뒤 다시 시도해 주세요."),

    // --- upload: request shape ----------------------------------------------
    NO_FILE_SUBMITTED(HttpStatus.BAD_REQUEST, "업로드할 파일을 선택해 주세요."),
    TOO_MANY_FILES(HttpStatus.CONTENT_TOO_LARGE, "한 번에 최대 %d개까지 업로드할 수 있습니다."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일 크기가 허용 범위(%s)를 초과했습니다."),

    // --- upload: per-file rejection -----------------------------------------
    EMPTY_FILE(HttpStatus.UNPROCESSABLE_CONTENT, "빈 파일은 업로드할 수 없습니다."),
    FILENAME_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "파일명이 없어 업로드할 수 없습니다."),
    FILENAME_TOO_LONG(HttpStatus.UNPROCESSABLE_CONTENT, "파일명이 너무 깁니다. 255바이트 이내로 줄여 주세요."),
    FILENAME_CONTROL_CHAR(HttpStatus.UNPROCESSABLE_CONTENT, "파일명에 사용할 수 없는 제어문자가 포함되어 있습니다."),
    FILENAME_RESERVED(HttpStatus.UNPROCESSABLE_CONTENT, "'%s'는 시스템 예약어라 파일명으로 사용할 수 없습니다."),
    FILENAME_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "사용할 수 없는 파일명입니다."),
    EXTENSION_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "'%s' 확장자는 차단되어 있어 업로드할 수 없습니다."),
    EXECUTABLE_CONTENT(HttpStatus.UNPROCESSABLE_CONTENT,
            "파일 내용이 실행 파일 또는 스크립트로 확인되어 업로드할 수 없습니다."),
    CONTENT_TYPE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT,
            "파일 내용이 '%s' 확장자와 일치하지 않습니다."),

    // Refused for capacity, not for anything wrong with the file. 507 rather
    // than 422 so a client can tell "this file is unacceptable" from "try again
    // later"; the other per-file rejections above are permanent for that file.
    STORAGE_QUOTA_EXCEEDED(HttpStatus.INSUFFICIENT_STORAGE,
            "저장 공간이 부족하여 업로드할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // --- infrastructure ------------------------------------------------------
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "관리자 토큰이 필요합니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "이 경로에서 지원하지 않는 요청 방식입니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    STORAGE_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String messageTemplate;

    ApiErrorCode(HttpStatus status, String messageTemplate) {
        this.status = status;
        this.messageTemplate = messageTemplate;
    }

    public HttpStatus status() {
        return status;
    }

    public String message(Object... args) {
        return args.length == 0 ? messageTemplate : String.format(messageTemplate, args);
    }
}
