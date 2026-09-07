package com.urlshortener.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnowflakeIdGenerator 單元測試
 */
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("唯一性測試: 連續產生 1000 個 ID 無重複且遞增")
    void testNextIdUniquenessAndOrdering() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        Set<Long> ids = new HashSet<>();
        long prevId = -1;

        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertThat(id).isGreaterThan(prevId);
            assertThat(ids.add(id)).isTrue();
            prevId = id;
        }
    }
}
