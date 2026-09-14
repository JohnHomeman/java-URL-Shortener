package com.urlshortener.repository;

import com.urlshortener.entity.AccessLog;

import java.util.List;

/**
 * 自訂存取日誌批次操作介面 (引用 Spec §3.2)
 */
public interface AccessLogRepositoryCustom {

    /**
     * JDBC 批次插入存取日誌
     */
    void batchInsert(List<AccessLog> accessLogs);
}
