package com.urlshortener.component;

import org.springframework.stereotype.Component;

/**
 * Base62 編碼與解碼組件 (0-9, a-z, A-Z)
 */
@Component
public class Base62Encoder {

    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = BASE62_CHARS.length(); // 62

    /**
     * 將非負整數編碼為 Base62 字串
     *
     * @param number 待編碼整數 (>= 0)
     * @return Base62 字串
     */
    public String encode(long number) {
        if (number < 0) {
            throw new IllegalArgumentException("Number must be non-negative for Base62 encoding: " + number);
        }
        if (number == 0) {
            return "0";
        }

        StringBuilder sb = new StringBuilder();
        while (number > 0) {
            int remainder = (int) (number % BASE);
            sb.append(BASE62_CHARS.charAt(remainder));
            number /= BASE;
        }
        return sb.reverse().toString();
    }

    /**
     * 將 Base62 字串解碼為十進位長整數
     *
     * @param str Base62 字串
     * @return 解碼後的長整數
     */
    public long decode(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("Base62 string cannot be null or empty");
        }

        long result = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int digit = BASE62_CHARS.indexOf(c);
            if (digit == -1) {
                throw new IllegalArgumentException("Invalid Base62 character encountered: " + c);
            }
            result = result * BASE + digit;
        }
        return result;
    }
}
