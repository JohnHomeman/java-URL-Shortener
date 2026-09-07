package com.urlshortener.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Base62Encoder 單元測試 (引用 Spec §7.1)
 */
class Base62EncoderTest {

    private final Base62Encoder encoder = new Base62Encoder();

    @ParameterizedTest(name = "編碼測試: number={0} -> expected={1}")
    @CsvSource({
            "0, 0",
            "1, 1",
            "10, A",
            "35, Z",
            "36, a",
            "61, z",
            "62, 10",
            "4294967295, 4gfFC3"
    })
    @DisplayName("Table-Driven: 驗證 Base62 編碼正確性")
    void testEncode(long number, String expected) {
        assertThat(encoder.encode(number)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "解碼測試: str={0} -> expected={1}")
    @CsvSource({
            "0, 0",
            "1, 1",
            "A, 10",
            "Z, 35",
            "a, 36",
            "z, 61",
            "10, 62",
            "4gfFC3, 4294967295"
    })
    @DisplayName("Table-Driven: 驗證 Base62 解碼正確性")
    void testDecode(String str, long expected) {
        assertThat(encoder.decode(str)).isEqualTo(expected);
    }

    @Test
    @DisplayName("例外情境: 傳入負數拋出 IllegalArgumentException")
    void testNegativeNumberThrowsException() {
        assertThatThrownBy(() -> encoder.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("例外情境: 傳入非法字元解碼拋出 IllegalArgumentException")
    void testInvalidCharacterThrowsException() {
        assertThatThrownBy(() -> encoder.decode("abc#123"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
