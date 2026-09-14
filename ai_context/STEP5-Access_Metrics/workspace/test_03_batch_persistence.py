#!/usr/bin/env python3
"""
情境 C 驗收腳本：排程批次回寫持久化驗收 (引用 Spec §7.2 情境 C)
"""
import requests
import time
import sys

BASE_URL = "http://localhost:8080"

def test_batch_persistence():
    print("[1] 觸發轉址累積點擊與日誌 ...")
    for _ in range(10):
        requests.get(f"{BASE_URL}/mdn-302", allow_redirects=False)

    print("[2] 等待 11 秒讓 MetricsPersistenceScheduler 執行定時批次回寫 MySQL ...")
    time.sleep(11)

    print("[3] 查詢存取日誌，確認 MySQL 已寫入數據 ...")
    resp = requests.get(f"{BASE_URL}/api/v1/urls/mdn-302/logs?page=1&size=10")
    if resp.status_code == 200:
        data = resp.json().get("data", {})
        total_elements = data.get("total_elements", 0)
        print(f"  -> MySQL access_logs 筆數: {total_elements}")
        if total_elements > 0:
            print("✅ [情境 C 驗收成功] 排程批次回寫 MySQL 成功！")
        else:
            print("⚠️ 尚無日誌寫入")
    else:
        print(f"❌ 查詢失敗: {resp.text}")

if __name__ == "__main__":
    test_batch_persistence()
