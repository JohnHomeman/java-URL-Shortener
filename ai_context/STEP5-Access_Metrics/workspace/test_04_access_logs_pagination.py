#!/usr/bin/env python3
"""
情境 D 驗收腳本：存取日誌分頁與時間篩選查詢 (引用 Spec §7.2 情境 D)
"""
import requests
import sys

BASE_URL = "http://localhost:8080"

def test_pagination():
    print("[1] 測試分頁查詢 (page=1, size=5) ...")
    resp = requests.get(f"{BASE_URL}/api/v1/urls/mdn-302/logs?page=1&size=5")
    if resp.status_code != 200:
        print(f"❌ 分頁查詢失敗: {resp.text}")
        sys.exit(1)

    data = resp.json().get("data", {})
    items = data.get("items", [])
    print(f"  -> 本頁筆數: {len(items)}, page: {data.get('page')}, size: {data.get('size')}, total_elements: {data.get('total_elements')}")

    print("[2] 測試無效時間區間 (start_time > end_time) 應回傳 400 (40003) ...")
    invalid_time_resp = requests.get(
        f"{BASE_URL}/api/v1/urls/mdn-302/logs?start_time=2026-09-10T00:00:00&end_time=2026-09-01T00:00:00"
    )
    if invalid_time_resp.status_code == 400 and invalid_time_resp.json().get("code") == 40003:
        print("  -> 正確攔截無效時間區間 (HTTP 400, code: 40003)")
    else:
        print(f"❌ 攔截失敗: {invalid_time_resp.text}")
        sys.exit(1)

    print("[3] 測試無效分頁參數 (page=0) 應回傳 400 (40004) ...")
    invalid_page_resp = requests.get(f"{BASE_URL}/api/v1/urls/mdn-302/logs?page=0&size=20")
    if invalid_page_resp.status_code == 400 and invalid_page_resp.json().get("code") == 40004:
        print("  -> 正確攔截無效分頁參數 (HTTP 400, code: 40004)")
    else:
        print(f"❌ 攔截失敗: {invalid_page_resp.text}")
        sys.exit(1)

    print("✅ [情境 D 驗收成功] 存取日誌分頁與時間篩選功能正常！")

if __name__ == "__main__":
    test_pagination()
