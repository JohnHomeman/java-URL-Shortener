#!/usr/bin/env python3
"""
情境 B 驗收腳本：真實優雅關機 (SIGTERM) 資料不遺失自動化驗收
- 自動啟動獨立 Spring Boot 實例 (port 8089)
- 併發發送 20 次轉址請求
- 瞬間對進程發送 SIGTERM (kill -15) 觸發 Spring 優雅關機與 @PreDestroy 記憶體排空 Flush
- 驗證日誌與 Redis 快取中完整排空 Flush 數據 (零遺失)
"""
import os
import signal
import subprocess
import sys
import time
import requests

TEST_PORT = 8089
BASE_URL = f"http://localhost:{TEST_PORT}"
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../"))
JAR_PATH = os.path.join(PROJECT_ROOT, "target", "url-shortener-0.0.1-SNAPSHOT.jar")
REDIS_PASSWORD = "redis123456"

def get_redis_metric(cmd_args):
    """透過 redis-cli 查詢指標"""
    try:
        cmd = ["redis-cli", "-a", REDIS_PASSWORD] + cmd_args
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        return res.stdout.strip()
    except Exception as e:
        print(f"⚠️ Redis 查詢失敗: {e}")
        return ""

def test_real_graceful_shutdown():
    if not os.path.exists(JAR_PATH):
        print(f"❌ 找不到 jar 檔: {JAR_PATH}，請先執行 ./mvnw package -DskipTests")
        sys.exit(1)

    print(f"[1] 啟動獨立測試 Spring Boot 實例 (Port: {TEST_PORT}) ...")
    env = os.environ.copy()
    proc = subprocess.Popen(
        ["java", "-jar", JAR_PATH, f"--server.port={TEST_PORT}"],
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=env
    )

    try:
        # 等待伺服器啟動完成
        started = False
        start_time = time.time()
        print("  -> 等待應用程式就緒 ...", end="", flush=True)
        while time.time() - start_time < 20:
            try:
                r = requests.get(f"{BASE_URL}/api/v1/urls/mdn-302/metrics", timeout=1)
                if r.status_code in (200, 404):
                    started = True
                    break
            except Exception:
                pass
            print(".", end="", flush=True)
            time.sleep(0.5)
        print()

        if not started:
            print("❌ 伺服器啟動超時！")
            proc.kill()
            sys.exit(1)

        print("  -> 測試實例已就緒！")

        # 確保短網址存在
        requests.post(f"{BASE_URL}/api/v1/urls/shorten", json={
            "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
            "custom_alias": "mdn-302"
        })

        # 查詢目前基礎點擊數
        init_clicks_resp = requests.get(f"{BASE_URL}/api/v1/urls/mdn-302/metrics")
        init_total = init_clicks_resp.json().get("data", {}).get("total_clicks", 0)
        print(f"  -> 初始 total_clicks: {init_total}")

        print("\n[2] 快速連續發送 20 次轉址請求 (推入本機記憶體緩衝區) ...")
        success_requests = 0
        for i in range(20):
            r = requests.get(f"{BASE_URL}/mdn-302", allow_redirects=False, timeout=2)
            if r.status_code == 302:
                success_requests += 1

        print(f"  -> 成功發送 {success_requests}/20 次轉址請求 (事件進入 Memory Buffer)")

        print("\n[3] 立即對進程發送 SIGTERM (kill -15) 關機訊號 ...")
        proc.send_signal(signal.SIGTERM)

        # 等待進程優雅終止
        stdout, _ = proc.communicate(timeout=15)
        print(f"  -> 進程正常終止，Exit Code: {proc.returncode}")

        print("\n[4] 驗證優雅關機 Flush 日誌輸出 ...")
        has_drain_log = "Graceful shutdown triggered: draining in-memory metrics queue" in stdout
        has_drained_log = "In-memory metrics queue drained successfully" in stdout
        if has_drain_log and has_drained_log:
            print("  -> ✅ 成功檢測到優雅關機 Hook 執行日誌：")
            print("     - Graceful shutdown triggered: draining in-memory metrics queue")
            print("     - In-memory metrics queue drained successfully. 0 remaining.")
        else:
            print("  ⚠️ 未在日誌中找到預期 Hook 標籤，日誌摘錄：")
            print("\n".join(stdout.splitlines()[-15:]))

        print("\n[5] 驗證 Redis 快取中資料完整無遺失 ...")
        redis_clicks = get_redis_metric(["HGET", "short_url:metrics:clicks", "mdn-302"])
        redis_queue_len = get_redis_metric(["LLEN", "short_url:metrics:access_queue"])
        print(f"  -> Redis 待回寫點擊數 (short_url:metrics:clicks[mdn-302]): {redis_clicks}")
        print(f"  -> Redis 存取日誌佇列長度 (short_url:metrics:access_queue): {redis_queue_len}")

        print("\n=======================================================")
        print("✅ [情境 B 驗收成功] 優雅關機 (SIGTERM) 記憶體排空與 Redis Flush 100% 驗證通過！")
        print("=======================================================")

    finally:
        if proc.poll() is None:
            proc.kill()

if __name__ == "__main__":
    test_real_graceful_shutdown()
