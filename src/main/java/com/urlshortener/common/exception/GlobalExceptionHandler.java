package com.urlshortener.common.exception;

import com.urlshortener.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全域異常攔截器 (引用 Spec §3.2, §5.2)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 自訂業務異常攔截
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business exception occurred: code={}, status={}, message={}", ex.getCode(), ex.getHttpStatus(), ex.getMessage());
        HttpStatus status = ex.getHttpStatus() != null ? ex.getHttpStatus() : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    /**
     * 參數校驗異常攔截 (400 Bad Request, code: 40001)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        List<FieldError> fieldErrors = bindingResult.getFieldErrors();
        String errorMsg = fieldErrors.stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        String fullMsg = "Invalid request parameters: [" + errorMsg + "]";
        log.warn("Validation error: {}", fullMsg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(40001, fullMsg));
    }

    /**
     * 靜態資源或路徑不存在異常 (404 Not Found, code: 40401)
     */
    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(Exception ex) {
        log.warn("Resource or Handler not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(40401, "Resource not found"));
    }

    /**
     * 不支援之 HTTP Method (405 Method Not Allowed, code: 40501)
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not allowed: {}", ex.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(40501, "Method not allowed"));
    }

    /**
     * 未捕捉系統異常攔截 (500 Internal Server Error, code: 50000)
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Throwable ex) {
        log.error("Unhandled exception caught by GlobalExceptionHandler", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(50000, "Internal server error"));
    }
}
