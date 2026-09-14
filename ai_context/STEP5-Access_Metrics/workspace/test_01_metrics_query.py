#!/usr/bin/env python3
"""
情境 A 驗收腳本：轉址觸發點擊與即時指標查詢 (引用 Spec §7.2 情境 A)
"""
import requests
import sys
import time

BASE_URL = "http://localhost:8080"

def test_metrics_query():
    print("[1] 建立測試短網址: mdn-302 ...")
    create_payload = {
        "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
        "custom_alias": "mdn-302"
    }
    resp = requests.post(f"{BASE_URL}/api/v1/urls/shorten", json=create_payload)
    if resp.status_code not in (200, 409):
        print(f"❌ 建立短網址失敗: {resp.text}")
        sys.exit(1)
    print("  -> 短網址已就緒")

    print("[2] 連續發送 5 次轉址請求 (GET /mdn-302) ...")
    for i in range(5):
        r = requests.get(f"{BASE_URL}/mdn-302", allow_redirects=False)
        if r.status_code != 302:
            print(f"❌ 轉址請求失敗 (第 {i+1} 次): HTTP {r.status_code}")
            sys.exit(1)
    print("  -> 5 次轉址請求完成 (HTTP 302)")

    print("[3] 查詢即時統計指標 (GET /api/v1/urls/mdn-302/metrics) ...")
    metrics_resp = requests.get(f"{BASE_URL}/api/v1/urls/mdn-302/metrics")
    if metrics_resp.status_code != 200:
        print(f"❌ 查詢指標失敗: HTTP {metrics_resp.status_code}, {metrics_resp.text}")
        sys.exit(1)

    data = metrics_resp.json().get("data", {})
    total_clicks = data.get("total_clicks", 0)
    print(f"  -> total_clicks = {total_clicks}")

    if total_clicks >= 5:
        print("✅ [情境 A 驗收成功] 即時點擊數正確聚合！")
    else:
        print(f"❌ [情境 A 驗收失敗] 預期 total_clicks >= 5，實際為: {total_clicks}")
        sys.exit(1)

if __name__ == "__main__":
    test_metrics_query()
