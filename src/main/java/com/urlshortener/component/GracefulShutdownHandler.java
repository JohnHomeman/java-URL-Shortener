package com.urlshortener.component;

import com.urlshortener.service.MetricsBufferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * 優雅關機監聽器 (引用 Spec §2, §3.3)
 * 監聽 Spring ContextClosedEvent 事件，確保應用程式停機時記憶體緩衝資料 100% 排空至 Redis
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownHandler implements ApplicationListener<ContextClosedEvent> {

    private final MetricsBufferService metricsBufferService;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Received Spring ContextClosedEvent. Initiating graceful shutdown of metrics buffer...");
        metricsBufferService.shutdownAndFlush();
    }
}
