package com.urlshortener.repository;

import com.urlshortener.entity.AccessLog;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 存取日誌自訂批次操作實作 (引用 Spec §3.2)
 */
@Repository
@RequiredArgsConstructor
public class AccessLogRepositoryCustomImpl implements AccessLogRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void batchInsert(List<AccessLog> accessLogs) {
        if (accessLogs == null || accessLogs.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO access_logs (short_key, ip, user_agent, referer, accessed_at) VALUES (?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, accessLogs, accessLogs.size(), (ps, log) -> {
            ps.setString(1, log.getShortKey());
            ps.setString(2, log.getIp() != null ? log.getIp() : "");
            ps.setString(3, log.getUserAgent() != null ? log.getUserAgent() : "");
            ps.setString(4, log.getReferer() != null ? log.getReferer() : "");
            ps.setTimestamp(5, Timestamp.from(log.getAccessedAt() != null ? log.getAccessedAt() : java.time.Instant.now()));
        });
    }
}
