# 存取指標模組現狀 (Access Metrics Module State)

## 1. 模組定位與職責
- **模組名稱**：`access_metrics` (v0.4.0)
- **職責**：
  - 轉址成功時非同步收集存取事件（IP, User-Agent, Referer, 時間戳）。
  - 本機記憶體有界佇列緩衝（`MetricsBufferService`），消除轉址主線程 I/O 阻塞。
  - 定時/定量以 Pipeline 批次寫入 Redis 快取（點擊計數 `short_url:metrics:clicks` + 日誌佇列 `short_url:metrics:access_queue`）。
  - 優雅關機排空（`GracefulShutdownHandler`），服務停止時 100% 同步 Flush 至 Redis，防數據遺失。
  - 後台排程批次回寫（`MetricsPersistenceScheduler`），每 10 秒原子轉移並持久化至 MySQL `short_urls.click_count` 與 `access_logs`。
  - 統計指標即時聚合查詢 (`GET /api/v1/urls/{shortKey}/metrics`) 與存取日誌分頁查詢 (`GET /api/v1/urls/{shortKey}/logs`)。

## 2. 資料結構與介面
- **資料表**：`access_logs` (主鍵 ID, short_key, ip, user_agent, referer, accessed_at) 及 `short_urls.click_count`。
- **Redis 快取鍵**：
  - `short_url:metrics:clicks` (Hash): 即時點擊增量
  - `short_url:metrics:clicks:syncing` (Hash): 排程回寫暫存鍵
  - `short_url:metrics:access_queue` (List): 存取日誌佇列
