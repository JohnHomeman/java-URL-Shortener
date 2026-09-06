package com.urlshortener.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 全域統一 API 回應封裝結構 (引用 Spec §3.1)
 *
 * @param <T> 回應資料型態
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 狀態碼 (0 代表成功，大於 0 代表異常)
     */
    private Integer code;

    /**
     * 回應訊息
     */
    private String message;

    /**
     * 實質負載物件
     */
    private T data;

    /**
     * 伺服器時間戳記 (毫秒)
     */
    private Long timestamp;

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(0)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> error(Integer code, String message) {
        return error(code, message, null);
    }

    public static <T> ApiResponse<T> error(Integer code, String message, T data) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
