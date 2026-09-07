package com.urlshortener.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 系統業務錯誤碼枚舉 (引用 Spec §5.1)
 */
@Getter
public enum ErrorCode {

    INVALID_PARAMS(HttpStatus.BAD_REQUEST, 40001, "Invalid request parameters: %s"),
    INVALID_ORIGINAL_URL(HttpStatus.BAD_REQUEST, 40002, "Invalid original URL format"),
    RECURSIVE_URL(HttpStatus.BAD_REQUEST, 40003, "Recursive self-referencing URL is not allowed"),
    RESERVED_KEYWORD(HttpStatus.BAD_REQUEST, 40004, "Custom alias contains reserved keyword: %s"),
    INVALID_CUSTOM_ALIAS_FORMAT(HttpStatus.BAD_REQUEST, 40005, "Invalid custom alias format"),
    CUSTOM_ALIAS_ALREADY_EXISTS(HttpStatus.CONFLICT, 40901, "Custom alias already exists: %s"),
    SHORT_URL_NOT_FOUND(HttpStatus.NOT_FOUND, 40401, "Short URL not found: %s"),
    SHORT_URL_EXPIRED(HttpStatus.GONE, 41001, "Short URL has expired"),
    SHORT_URL_DISABLED(HttpStatus.FORBIDDEN, 40301, "Short URL is disabled"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 50000, "Internal server error");

    private final HttpStatus httpStatus;
    private final int code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, int code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
