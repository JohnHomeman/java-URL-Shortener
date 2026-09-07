package com.urlshortener.component;

import com.urlshortener.common.exception.BusinessException;
import com.urlshortener.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * URL 與自訂別名校驗組件 (引用 Spec §3.1, §3.3, §6.4)
 */
@Component
public class UrlValidator {

    private static final int MAX_URL_LENGTH = 2048;
    private static final Pattern CUSTOM_ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{4,16}$");

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "api",
            "health",
            "actuator",
            "swagger",
            "swagger-ui",
            "v3",
            "error",
            "metrics",
            "static",
            "docs",
            "favicon.ico",
            "admin"
    );

    /**
     * 當設定檔未指定或網址解析失敗時之安全後備主機名稱 (Fallback Host)
     */
    private static final String DEFAULT_FALLBACK_HOST = "localhost";

    /**
     * 本縮網址服務之網域名稱主機 (Host)，供防自指向轉址死循環檢查使用
     */
    private final String serviceDomainHost;

    /**
     * 建構子：從 application.yml 中的 app.short-url.domain 讀取服務網址並解析 Host
     *
     * @param domainUrl 本服務對外網址 (如 http://localhost:8080 或 https://sho.rt)
     */
    public UrlValidator(@Value("${app.short-url.domain:http://localhost:8080}") String domainUrl) {
        String host = DEFAULT_FALLBACK_HOST;
        if (domainUrl != null && !domainUrl.isBlank()) {
            try {
                // 從設定的網址中提取純主機名稱 (Host)，例如 "localhost" 或 "sho.rt"
                URI uri = URI.create(domainUrl.trim());
                if (uri.getHost() != null) {
                    host = uri.getHost().toLowerCase(Locale.ROOT);
                }
            } catch (Exception ignored) {
                // 若解析異常則維持預設後備主機名
            }
        }
        this.serviceDomainHost = host;
    }

    /**
     * 原始網址校驗與正規化
     *
     * @param originalUrl 待校驗之原始網址
     * @return 正規化後之網址
     */
    public String validateAndNormalizeUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ORIGINAL_URL);
        }

        String trimmedUrl = originalUrl.trim();
        if (trimmedUrl.length() > MAX_URL_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ORIGINAL_URL);
        }

        URI uri;
        try {
            uri = URI.create(trimmedUrl);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_ORIGINAL_URL);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new BusinessException(ErrorCode.INVALID_ORIGINAL_URL);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ORIGINAL_URL);
        }

        host = host.toLowerCase(Locale.ROOT);

        // 防自指向迴圈與內網 IP 指向 (SSRF 防禦)
        if (isSelfReferencingOrLocalHost(host)) {
            throw new BusinessException(ErrorCode.RECURSIVE_URL);
        }

        return trimmedUrl;
    }

    /**
     * 自訂別名格式與保留字校驗
     *
     * @param customAlias 使用者輸入之自訂別名
     */
    public void validateCustomAlias(String customAlias) {
        if (customAlias == null || customAlias.isBlank()) {
            return;
        }

        if (RESERVED_KEYWORDS.contains(customAlias.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.RESERVED_KEYWORD, customAlias);
        }

        if (!CUSTOM_ALIAS_PATTERN.matcher(customAlias).matches()) {
            throw new BusinessException(ErrorCode.INVALID_CUSTOM_ALIAS_FORMAT);
        }
    }

    /**
     * 檢查輸入之 Host 是否為本系統服務網域 (防自指向死循環) 或本機/內網私有 IP (SSRF 防禦)
     *
     * @param host 待檢查之主機名稱或 IP 字串
     * @return true 若為自指向或私有內網 IP
     */
    private boolean isSelfReferencingOrLocalHost(String host) {
        // 1. 防自指向迴圈：長網址與本縮網址服務域名相同
        if (host.equals(serviceDomainHost)) {
            return true;
        }
        // 2. 本機環回位址 (Loopback IP / Localhost)
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0")) {
            return true;
        }
        // 3. RFC 1918 私有內網 IP：
        //    - Class A: 10.0.0.0/8
        //    - Class C: 192.168.0.0/16
        //    - Class B: 172.16.0.0/12 (172.16.x.x ~ 172.31.x.x，如 Docker 內網)
        return host.startsWith("10.") || host.startsWith("192.168.")
                || (host.startsWith("172.") && isPrivateClassB(host));
    }

    /**
     * 判斷是否為 Class B 私有內網 IP (172.16.0.0 ~ 172.31.255.255)
     * 因 172. 開頭中僅第二區段數值介於 16 至 31 者為私有內網，其餘為合法公網 IP
     *
     * @param host IP 字串 (如 172.17.0.2)
     * @return true 若第二區段數字介於 16 ~ 31
     */
    private boolean isPrivateClassB(String host) {
        try {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                int secondOctet = Integer.parseInt(parts[1]);
                return secondOctet >= 16 && secondOctet <= 31;
            }
        } catch (NumberFormatException ignored) {
            // 若非標準數字格式則視為非 Class B IP
        }
        return false;
    }
}
