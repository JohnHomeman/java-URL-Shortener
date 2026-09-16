import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// 自訂指標宣告 (Spec §4.2)
export const locationCorrectRate = new Rate('location_correct');
export const redirectDurationTrend = new Trend('redirect_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 負載曲線與閾值配置 (Spec §3.3, §3.4, §6.2)
export const options = {
  stages: [
    { duration: '30s', target: 10 },   // Warm-up: 30s 暖身至 10 VU
    { duration: '1m', target: 200 },    // Ramp-up: 1m 線性拉升至 200 VU
    { duration: '2m', target: 200 },    // Sustain: 2m 維持 200 VU 高壓 (熱 Key 模式)
    { duration: '30s', target: 0 },     // Ramp-down: 30s 平滑收尾至 0 VU
  ],
  thresholds: {
    // 錯誤率 < 1% (Spec §3.4)
    http_req_failed: ['rate<0.01'],
    // P95 延遲 < 200ms, P99 延遲 < 500ms (Spec §3.4)
    http_req_duration: ['p(95)<200', 'p(99)<500'],
    // Location Header 正確率必須 100% (Spec §3.4)
    location_correct: ['rate==1'],
  },
};

// Setup 階段：建立 1 組熱點短網址 (Spec §3.1, §4.1, §6.2)
export function setup() {
  const shortUrlList = [];
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const originalUrl = 'https://k6-press-test.example.com/target/hot_key_test';
  const payload = JSON.stringify({
    original_url: originalUrl,
    custom_alias: null,
    ttl_in_seconds: null,
  });

  const res = http.post(`${BASE_URL}/api/v1/urls/shorten`, payload, params);

  if (res.status !== 200) {
    fail(`Setup failed: HTTP status ${res.status}, body: ${res.body}`);
  }

  try {
    const body = JSON.parse(res.body);
    if (body.code === 0 && body.data && body.data.short_key) {
      shortUrlList.push({
        shortKey: body.data.short_key,
        originalUrl: body.data.original_url || originalUrl,
      });
    } else {
      fail(`Setup failed: unexpected response body ${res.body}`);
    }
  } catch (e) {
    fail(`Setup failed: json parse error ${e.message}`);
  }

  // 驗證 Setup 資料完整性 (Spec §7.2 情境 D)
  if (shortUrlList.length !== 1) {
    fail(`Setup failed: expected 1 item, but got ${shortUrlList.length}`);
  }

  return shortUrlList;
}

// 壓測主體迭代邏輯 — 熱 Key 模式 (Spec §6.2 決策 A, §7.2 情境 C)
export default function (data) {
  // 固定只打第一筆短網址 (index 0)，製造極致熱 Key 情境
  const item = data[0];

  // redirects: 0 關閉自動跳轉，直接驗證 302 回應與 Location Header (Spec §3.2, §6.1)
  const res = http.get(`${BASE_URL}/${item.shortKey}`, {
    redirects: 0,
  });

  const locationHeader = res.headers['Location'] || res.headers['location'] || '';
  const isStatus302 = res.status === 302;
  const isLocationMatch = locationHeader === item.originalUrl;

  // 記錄自訂指標 (Spec §4.2)
  locationCorrectRate.add(isLocationMatch);
  redirectDurationTrend.add(res.timings.duration);

  // 雙重驗證斷言 (Spec §3.2, §5.2)
  check(res, {
    'status is 302': (r) => r.status === 302,
    'location header matches original url': () => isLocationMatch,
  });
}
