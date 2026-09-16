# 壓測驗收報告: Stage 4 全域轉址高併發壓力測試 (test_01_full_load)

## 1. 測試概況

- **測試目標**：四階段負載曲線（Warm-up → Ramp-up → Sustain 200 VU → Ramp-down），驗證系統在全新純淨環境（清空 `short_urls` 與 `access_logs`、FlushDB Redis、重啟 JVM）下，高併發時的 302 跳轉正確性、Location Header 精確度、P95/P99 延遲與錯誤率。
- **測試腳本**：`ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js`
- **執行時間**：4 分鐘 (240.3 秒)
- **併發規模 (VU)**：最高 200 VU
- **總請求量**：1,945,964 次（平均 **8,096.80 req/s**）

---

## 2. 閾值通過情況 (Thresholds Status)

| 指標 (Metric) | 期望標準 (Threshold) | 實測數值 (Actual) | 判定結果 |
| :--- | :--- | :--- | :--- |
| **P95 延遲 (`http_req_duration`)** | `p(95) < 200ms` | **46.08 ms** | **✅ PASS** |
| **P99 延遲 (`http_req_duration`)** | `p(99) < 500ms` | **68.96 ms** | **✅ PASS** |
| **請求錯誤率 (`http_req_failed`)** | `rate < 0.01` (1%) | **0.00%** (0 / 1,945,964) | **✅ PASS** |
| **跳轉位址正確率 (`location_correct`)** | `rate == 1.0` (100%) | **100.00%** (1,945,914 / 1,945,914) | **✅ PASS** |

---

## 3. 詳細效能與統計數據 (Detailed Summary)

### 3.1 核心 Checks 斷言

- **`status is 302`**：100.00% 通過（1,945,914 / 1,945,914）
- **`location header matches original url`**：100.00% 通過（1,945,914 / 1,945,914）
- **總斷言成功率**：100.00% (3,891,828 / 3,891,828)

### 3.2 延遲分佈 (Latency Distribution)

- **平均延遲 (avg)**：`17.14 ms`
- **最小延遲 (min)**：`351 µs`
- **中位數延遲 (med / P50)**：`18.20 ms`
- **P90 延遲**：`41.36 ms`
- **P95 延遲**：`46.08 ms`
- **P99 延遲**：`68.96 ms`
- **最大延遲 (max)**：`220.98 ms`

### 3.3 吞吐量與傳輸

- **HTTP 總請求數**：`1,945,964` (8,096.80 req/s)
- **總資料接收量 (data_received)**：`407 MB` (1.7 MB/s)
- **總資料傳送量 (data_sent)**：`147 MB` (613 kB/s)

---

## 4. 原始 k6 Summary 輸出

```text
  █ THRESHOLDS 

    http_req_duration
    ✓ 'p(95)<200' p(95)=46.08ms
    ✓ 'p(99)<500' p(99)=68.96ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%

    location_correct
    ✓ 'rate==1' rate=100.00%


  █ TOTAL RESULTS 

    checks_total.......: 3891828 16193.175985/s
    checks_succeeded...: 100.00% 3891828 out of 3891828
    checks_failed......: 0.00%   0 out of 3891828

    ✓ status is 302
    ✓ location header matches original url

    CUSTOM
    location_correct...............: 100.00% 1945914 out of 1945914
    redirect_duration..............: avg=17.140443 min=0.351    med=18.204  max=220.986  p(90)=41.368  p(95)=46.082  p(99)=68.96187

    HTTP
    http_req_duration..............: avg=17.14ms   min=351µs    med=18.2ms  max=220.98ms p(90)=41.36ms p(95)=46.08ms p(99)=68.96ms 
      { expected_response:true }...: avg=17.14ms   min=351µs    med=18.2ms  max=220.98ms p(90)=41.36ms p(95)=46.08ms p(99)=68.96ms 
    http_req_failed................: 0.00%   0 out of 1945964
    http_reqs......................: 1945964 8096.796033/s

    EXECUTION
    iteration_duration.............: avg=17.17ms   min=372.91µs med=18.24ms max=221.05ms p(90)=41.4ms  p(95)=46.11ms p(99)=68.98ms 
    iterations.....................: 1945914 8096.587992/s
    vus............................: 3       min=1                  max=200
    vus_max........................: 200     min=200                max=200

    NETWORK
    data_received..................: 407 MB  1.7 MB/s
    data_sent......................: 147 MB  613 kB/s
```

---

## 5. 結論與分析

1. **純淨環境重啟後效能卓越**：
   - 在徹底清空 MySQL (`short_urls`, `access_logs`) 與 Redis，並重新啟動乾淨 JVM 後，系統吞吐量達到 **8,096.80 req/s**。
   - **P95 延遲 46.08ms**（大幅低於 200ms），**P99 延遲 68.96ms**（大幅低於 500ms），所有閾值以 Exit Code 0 順利通過。
2. **極致正確性**：
   - 接近 195 萬次轉址請求中，錯誤率維持 **0.00%**，302 跳轉及 `Location` Header 比對達成 **100.00% 成功**。
