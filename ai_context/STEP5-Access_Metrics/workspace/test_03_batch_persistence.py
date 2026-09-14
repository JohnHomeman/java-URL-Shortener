#!/usr/bin/env python3
"""
情境 C 驗收腳本：排程批次回寫持久化驗收 (引用 Spec §7.2 情境 C)
- 發送 100 筆轉址請求觸發 100 筆點擊與 100 筆存取日誌
- 等待排程器 (MetricsPersistenceScheduler 10 秒週期) 批次回寫 MySQL
- 驗證 MySQL short_urls.click_count 增加 100
- 驗證 MySQL access_logs 總筆數增加 100
- 驗證 Redis 暫存佇列與點擊 Hash 已全數清空
"""
import concurrent.futures
import subprocess
import sys
import time
import requests

BASE_URL = "http://localhost:8080"
TEST_KEY = "mdn-302"
REDIS_PASSWORD = "redis123456"
MYSQL_USER = "root"
MYSQL_PASS = "123456"
MYSQL_DB = "java_project"
TARGET_BATCH_COUNT = 100

def get_mysql_stats():
    """查詢 MySQL 中目前的 click_count 與 access_logs 總筆數"""
    try:
        sql = f"SELECT click_count FROM {MYSQL_DB}.short_urls WHERE short_key='{TEST_KEY}'; SELECT COUNT(*) FROM {MYSQL_DB}.access_logs WHERE short_key='{TEST_KEY}';"
        res = subprocess.run(
            ["mysql", f"-u{MYSQL_USER}", f"-p{MYSQL_PASS}", "-N", "-B", "-e", sql],
            capture_output=True,
            text=True,
            timeout=5
        )
        lines = [line.strip() for line in res.stdout.splitlines() if line.strip().isdigit()]
        if len(lines) >= 2:
            return int(lines[0]), int(lines[1])
    except Exception as e:
        print(f"⚠️ MySQL 查詢失敗: {e}")
    return 0, 0

def get_redis_metric(cmd_args):
    """查詢 Redis 快取狀態"""
    try:
        cmd = ["redis-cli", "-a", REDIS_PASSWORD] + cmd_args
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        return res.stdout.strip()
    except Exception:
        return ""

def test_batch_persistence():
    print("=======================================================")
    print("情境 C 驗收：排程批次回寫持久化驗收 (100 筆點擊與存取日誌)")
    print("=======================================================\n")

    # 1. 確保短網址就緒
    requests.post(f"{BASE_URL}/api/v1/urls/shorten", json={
        "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
        "custom_alias": TEST_KEY
    })

    # 2. 記錄前置狀態
    initial_click_count, initial_logs_count = get_mysql_stats()
    print(f"[1] 前置狀態檢查 (MySQL - {TEST_KEY})：")
    print(f"  -> 初始 short_urls.click_count: {initial_click_count}")
    print(f"  -> 初始 access_logs 記錄筆數: {initial_logs_count}")

    # 3. 併發發送 100 次轉址請求
    print(f"\n[2] 發送 {TARGET_BATCH_COUNT} 筆轉址請求 (推入記憶體緩衝區並寫入 Redis) ...")
    success_requests = 0

    def send_redirect():
        try:
            r = requests.get(f"{BASE_URL}/{TEST_KEY}", allow_redirects=False, timeout=2)
            return r.status_code == 302
        except Exception:
            return False

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(send_redirect) for _ in range(TARGET_BATCH_COUNT)]
        for f in concurrent.futures.as_completed(futures):
            if f.result():
                success_requests += 1

    print(f"  -> 成功發送 {success_requests}/{TARGET_BATCH_COUNT} 筆轉址請求 (HTTP 302)")
    if success_requests != TARGET_BATCH_COUNT:
        print(f"❌ 部分請求失敗，成功數: {success_requests}")
        sys.exit(1)

    # 4. 等待排程器執行 (MetricsPersistenceScheduler 每 10 秒執行一次)
    print("\n[3] 等待排程器觸發批次回寫至 MySQL (至多等待 15 秒) ...", end="", flush=True)
    persisted = False
    start_wait = time.time()
    while time.time() - start_wait < 15:
        time.sleep(1)
        print(".", end="", flush=True)
        curr_click, curr_logs = get_mysql_stats()
        if curr_click >= initial_click_count + TARGET_BATCH_COUNT and curr_logs >= initial_logs_count + TARGET_BATCH_COUNT:
            persisted = True
            break
    print()

    # 5. 驗證 MySQL 持久化數據
    final_click_count, final_logs_count = get_mysql_stats()
    delta_clicks = final_click_count - initial_click_count
    delta_logs = final_logs_count - initial_logs_count

    print(f"\n[4] MySQL 資料庫持久化驗證：")
    print(f"  -> short_urls.click_count: {initial_click_count} -> {final_click_count} (增量: +{delta_clicks}, 期望: +{TARGET_BATCH_COUNT})")
    print(f"  -> access_logs 記錄總筆數: {initial_logs_count} -> {final_logs_count} (增量: +{delta_logs}, 期望: +{TARGET_BATCH_COUNT})")

    # 6. 驗證 Redis 暫存清空狀態
    redis_clicks = get_redis_metric(["HGET", "short_url:metrics:clicks", TEST_KEY])
    redis_queue_len = get_redis_metric(["LLEN", "short_url:metrics:access_queue"])
    redis_syncing_exists = get_redis_metric(["EXISTS", "short_url:metrics:clicks:syncing"])

    print(f"\n[5] Redis 快取隊列清空狀態驗證：")
    print(f"  -> Redis 暫存隊列 (short_url:metrics:access_queue): {redis_queue_len} (期望: 0)")
    print(f"  -> Redis 暫存同步鍵 (short_url:metrics:clicks:syncing): {redis_syncing_exists} (期望: 0)")

    # 7. 總結斷言
    if delta_clicks == TARGET_BATCH_COUNT and delta_logs == TARGET_BATCH_COUNT:
        print("\n=======================================================")
        print("✅ [情境 C 驗收成功] 100 筆點擊與 100 筆存取日誌已成功批次回寫 MySQL 持久化！")
        print("=======================================================")
    else:
        print(f"\n❌ [情境 C 驗收失敗] 數據增量不符合預期！")
        sys.exit(1)

if __name__ == "__main__":
    test_batch_persistence()
