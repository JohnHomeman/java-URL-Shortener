package com.urlshortener.common.exception;

import lombok.Getter;

/**
 * 自訂業務異常類別 (引用 Spec §3.2)
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final String message;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message) {
        super(message);
        this.code = 40000;
        this.message = message;
    }
}
