package com.urlshortener.component;

import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 雜湊計算組件
 */
@Component
public class HashGenerator {

    /**
     * 計算字串之 MurmurHash3 32-bit 雜湊值（轉為非負 64-bit Long，範圍 0 ~ 2^32 - 1）
     *
     * @param input 原始字串
     * @return 32-bit 無符號長整數
     */
    public long hash32(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input string cannot be null");
        }
        int hash = Hashing.murmur3_32_fixed()
                .hashString(input, StandardCharsets.UTF_8)
                .asInt();
        return Integer.toUnsignedLong(hash);
    }

    /**
     * 計算加鹽雜湊值（供碰撞重試使用）
     *
     * @param url 原始網址
     * @param retryCount 重試次數 (1, 2, 3...)
     * @return 32-bit 無符號長整數
     */
    public long hashWithSalt(String url, int retryCount) {
        String saltedUrl = url + "[SALT_" + retryCount + "]";
        return hash32(saltedUrl);
    }

    /**
     * 計算供快速反查比對用之 64-bit Hash 數值
     *
     * @param input 原始字串
     * @return 64-bit 雜湊數值 (非負)
     */
    public long hash64(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input string cannot be null");
        }
        long hash = Hashing.murmur3_128()
                .hashString(input, StandardCharsets.UTF_8)
                .asLong();
        return hash & Long.MAX_VALUE; // 保證非負數
    }
}
