package com.urlshortener.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 自訂業務異常類別 (引用 Spec §3.2, §5.1)
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final Integer code;
    private final String message;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    public BusinessException(ErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
        this.message = String.format(errorCode.getMessage(), args);
    }

    public BusinessException(HttpStatus httpStatus, Integer code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.httpStatus = (code != null && code >= 40900 && code < 41000) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message) {
        super(message);
        this.httpStatus = HttpStatus.BAD_REQUEST;
        this.code = 40000;
        this.message = message;
    }
}
