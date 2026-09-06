package com.urlshortener.common.response;

import com.urlshortener.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 統一回應封裝單元測試 (引用 Spec §7.1 Table-Driven)
 */
class ApiResponseTest {

    @Test
    @DisplayName("空物件成功封裝 - data: null, expected code: 0, msg: success")
    void testSuccessWithoutData() {
        ApiResponse<Void> response = ApiResponse.success();
        assertNotNull(response);
        assertEquals(0, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
        assertNotNull(response.getTimestamp());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTableDrivenCases")
    @DisplayName("Table-Driven 測試驗證 Spec §7.1")
    void testTableDriven(String caseName, Integer expectedCode, String expectedMessage, Object expectedData, Object actualOutput) {
        if (actualOutput instanceof ApiResponse<?> response) {
            assertEquals(expectedCode, response.getCode());
            assertEquals(expectedMessage, response.getMessage());
            assertEquals(expectedData, response.getData());
            assertNotNull(response.getTimestamp());
        }
    }

    private static Stream<Arguments> provideTableDrivenCases() {
        return Stream.of(
                Arguments.of(
                        "成功回應封裝",
                        0,
                        "success",
                        "pong",
                        ApiResponse.success("pong")
                ),
                Arguments.of(
                        "業務異常封裝",
                        40001,
                        "bad req",
                        null,
                        (Runnable) () -> {
                            BusinessException ex = new BusinessException(40001, "bad req");
                            ApiResponse<Void> res = ApiResponse.error(ex.getCode(), ex.getMessage());
                            assertEquals(40001, res.getCode());
                            assertEquals("bad req", res.getMessage());
                            assertNull(res.getData());
                        } instanceof Runnable ? ApiResponse.error(40001, "bad req") : null
                ),
                Arguments.of(
                        "空物件成功封裝",
                        0,
                        "success",
                        null,
                        ApiResponse.success(null)
                )
        );
    }
}
