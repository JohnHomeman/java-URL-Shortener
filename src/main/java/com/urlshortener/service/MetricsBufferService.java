package com.urlshortener.service;

import com.urlshortener.component.MetricsRedisHelper;
import com.urlshortener.dto.AccessEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本機記憶體緩衝與非同步批次寫入 Redis 服務 (引用 Spec §3.1, §3.3)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsBufferService {

    private static final int QUEUE_CAPACITY = 50_000;
    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 200L;

    private final BlockingQueue<AccessEvent> eventQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final MetricsRedisHelper metricsRedisHelper;

    private ExecutorService workerExecutor;

    @PostConstruct
    public synchronized void startWorker() {
        if (workerExecutor == null || workerExecutor.isShutdown()) {
            running.set(true);
            workerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "metrics-buffer-worker");
                t.setDaemon(true);
                return t;
            });
            workerExecutor.submit(this::processQueueLoop);
            log.info("MetricsBufferService worker started successfully.");
        }
    }

    /**
     * 非同步記錄存取事件 (主線程非阻塞推入佇列，引用 Spec §3.1)
     *
     * @param event 存取事件物件 (包含短碼、IP、User-Agent、Referer、時間戳)
     * @return 若成功推入記憶體佇列回傳 true；若觸發降級直寫 Redis 亦回傳處理狀態
     */
    public boolean recordAccessEvent(AccessEvent event) {
        if (event == null) {
            return false;
        }

        // 1. 若系統已收到關機訊號 (running == false)，佇列已關閉，啟用安全保底直寫 Redis，防止關機瞬間丟失資料
        if (!running.get()) {
            log.warn("Application is shutting down. Falling back to direct Redis flush for key: {}", event.getShortKey());
            flushSingleEventDirectly(event);
            return true;
        }

        // 2. 使用 offer() 非阻塞方式推入記憶體佇列：
        //    - 若佇列有空位：瞬間推入成功並回傳 true (< 0.01ms)，完全不阻塞轉址主線程跳轉
        //    - 若佇列已滿 (達 50,000 筆上限)：立即回傳 false，絕不卡死請求
        boolean offered = eventQueue.offer(event);

        // 3. 背壓降級防護 (Backpressure Fallback)：
        //    當極端高併發導致記憶體佇列塞滿時，直接繞過佇列同步寫入 Redis，兼顧轉址不卡頓與資料零遺失
        if (!offered) {
            log.warn("Metrics event queue is full ({}/{}). Emergency direct flush for key: {}",
                    eventQueue.size(), QUEUE_CAPACITY, event.getShortKey());
            flushSingleEventDirectly(event);
        }
        return offered;
    }

    /**
     * 背景執行緒常態消費佇列並批次寫入 Redis (引用 Spec §3.1)
     */
    private void processQueueLoop() {
        List<AccessEvent> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlushTime = System.currentTimeMillis();

        while (running.get() || !eventQueue.isEmpty()) {
            try {
                AccessEvent event = eventQueue.poll(50, TimeUnit.MILLISECONDS);
                if (event != null) {
                    batch.add(event);
                }

                long now = System.currentTimeMillis();
                boolean batchFull = batch.size() >= BATCH_SIZE;
                boolean timeElapsed = (!batch.isEmpty() && (now - lastFlushTime >= FLUSH_INTERVAL_MS));

                if (batchFull || timeElapsed || (!running.get() && !batch.isEmpty())) {
                    flushBatchToRedis(batch);
                    batch.clear();
                    lastFlushTime = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Metrics buffer worker interrupted.");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in metrics buffer worker loop", e);
            }
        }

        // 結束迴圈前若 batch 還有殘留數據，進行最後寫入
        if (!batch.isEmpty()) {
            flushBatchToRedis(batch);
            batch.clear();
        }
    }

    /**
     * 優雅關機排空記憶體緩衝區並同步 Flush 至 Redis (引用 Spec §3.3)
     */
    @PreDestroy
    public synchronized void shutdownAndFlush() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Graceful shutdown triggered: draining in-memory metrics queue (current size: {})...", eventQueue.size());

        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workerExecutor.shutdownNow();
            }
        }

        // 排空所有剩餘事件
        List<AccessEvent> remainingEvents = new ArrayList<>();
        eventQueue.drainTo(remainingEvents);

        if (!remainingEvents.isEmpty()) {
            log.info("Flushing {} remaining metrics events to Redis synchronously...", remainingEvents.size());
            flushBatchToRedis(remainingEvents);
        }
        log.info("In-memory metrics queue drained successfully. 0 remaining.");
    }

    /**
     * 將事件列表聚合點擊數並寫入 Redis (Pipeline)
     */
    public void flushBatchToRedis(List<AccessEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        Map<String, Long> clicksMap = new HashMap<>();
        for (AccessEvent event : events) {
            if (event.getShortKey() != null) {
                clicksMap.merge(event.getShortKey(), 1L, Long::sum);
            }
        }

        // 1. 寫入點擊數
        metricsRedisHelper.incrementClicks(clicksMap);
        // 2. 寫入日誌佇列
        metricsRedisHelper.pushAccessLogs(events);
    }

    private void flushSingleEventDirectly(AccessEvent event) {
        if (event.getShortKey() != null) {
            metricsRedisHelper.incrementClicks(Map.of(event.getShortKey(), 1L));
        }
        metricsRedisHelper.pushAccessLogs(List.of(event));
    }

    public int getQueueSize() {
        return eventQueue.size();
    }
}
