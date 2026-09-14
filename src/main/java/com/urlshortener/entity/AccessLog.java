package com.urlshortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 短網址存取明細日誌實體 (引用 Spec §4.1)
 */
@Entity
@Table(name = "access_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_key", nullable = false, length = 16)
    private String shortKey;

    @Column(name = "ip", nullable = false, length = 45)
    private String ip;

    @Column(name = "user_agent", nullable = false, length = 512)
    private String userAgent;

    @Column(name = "referer", nullable = false, length = 1024)
    private String referer;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    @PrePersist
    protected void onCreate() {
        if (this.accessedAt == null) {
            this.accessedAt = Instant.now();
        }
        if (this.ip == null) {
            this.ip = "";
        }
        if (this.userAgent == null) {
            this.userAgent = "";
        }
        if (this.referer == null) {
            this.referer = "";
        }
    }
}
