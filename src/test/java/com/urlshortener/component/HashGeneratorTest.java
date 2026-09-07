package com.urlshortener.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HashGenerator 單元測試 (引用 Spec §7.1)
 */
class HashGeneratorTest {

    private final HashGenerator hashGenerator = new HashGenerator();

    @Test
    @DisplayName("MurmurHash3 一致性: 相同輸入輸出固定非負 32-bit Long 整數")
    void testHash32Consistency() {
        String url = "https://example.com";
        long hash1 = hashGenerator.hash32(url);
        long hash2 = hashGenerator.hash32(url);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isGreaterThanOrEqualTo(0L);
        assertThat(hash1).isLessThanOrEqualTo(4294967295L); // 2^32 - 1
    }

    @Test
    @DisplayName("加鹽雜湊: 不同重試次數產生不同 Hash 值")
    void testHashWithSalt() {
        String url = "https://example.com";
        long hash0 = hashGenerator.hash32(url);
        long hashSalt1 = hashGenerator.hashWithSalt(url, 1);
        long hashSalt2 = hashGenerator.hashWithSalt(url, 2);

        assertThat(hashSalt1).isNotEqualTo(hash0);
        assertThat(hashSalt2).isNotEqualTo(hashSalt1);
    }

    @Test
    @DisplayName("例外情境: null 輸入拋出 IllegalArgumentException")
    void testNullInputThrowsException() {
        assertThatThrownBy(() -> hashGenerator.hash32(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
