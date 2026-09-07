package com.urlshortener.component;

import com.urlshortener.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UrlValidator 單元測試 (引用 Spec §7.1)
 */
class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator("http://localhost:8080");

    @Test
    @DisplayName("正常網址校驗與正規化: HTTP 與 HTTPS 成功返回去除空白之網址")
    void testValidUrls() {
        String url = "   https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302   ";
        String normalized = validator.validateAndNormalizeUrl(url);
        assertThat(normalized).isEqualTo("https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://files.example.com",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "not-a-valid-url",
            "",
            "   "
    })
    @DisplayName("URL 格式非 HTTP/HTTPS 拋出 40002 INVALID_URL")
    void testInvalidUrls(String invalidUrl) {
        assertThatThrownBy(() -> validator.validateAndNormalizeUrl(invalidUrl))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(40002);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080/test",
            "http://localhost/something",
            "http://127.0.0.1:8080/api",
            "http://192.168.1.100/admin",
            "http://10.0.0.1/dashboard"
    })
    @DisplayName("URL 為自指向或內網 IP 拋出 40003 RECURSIVE_URL")
    void testSelfReferencingOrLocalUrls(String localUrl) {
        assertThatThrownBy(() -> validator.validateAndNormalizeUrl(localUrl))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(40003);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "my-custom_link",
            "promo-2026",
            "Abc123_-",
            "validAlias"
    })
    @DisplayName("自訂別名格式合法: 4-16 位英數_- 正常通過")
    void testValidCustomAlias(String validAlias) {
        assertThatCode(() -> validator.validateCustomAlias(validAlias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc",                              // 長度 < 4
            "this_is_a_very_long_alias_exceeded", // 長度 > 16
            "hello world",                      // 包含空白
            "promo#1",                          // 特殊字元 #
            "link?query=1"                      // 特殊字元 ?
    })
    @DisplayName("自訂別名格式不合法拋出 40005 INVALID_CUSTOM_ALIAS_FORMAT")
    void testInvalidCustomAliasFormat(String invalidAlias) {
        assertThatThrownBy(() -> validator.validateCustomAlias(invalidAlias))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(40005);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "api",
            "health",
            "actuator",
            "swagger",
            "error",
            "metrics",
            "static",
            "admin"
    })
    @DisplayName("自訂別名含系統保留字拋出 40004 RESERVED_KEYWORD")
    void testReservedKeywords(String reservedKeyword) {
        assertThatThrownBy(() -> validator.validateCustomAlias(reservedKeyword))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(40004);
                });
    }
}
