// Shared helpers for the k6 scenarios (hot-item.js, spread.js).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

export const cfg = {
  BASE_URL: __ENV.BASE_URL || 'http://host.docker.internal:8080',
  VUS: parseInt(__ENV.VUS || '50', 10),
  DURATION: __ENV.DURATION || '30s',
  STOCK: parseInt(__ENV.STOCK || '1000', 10),
  QTY: parseInt(__ENV.QTY || '1', 10),
  ITEMS: parseInt(__ENV.ITEMS || '100', 10),
  MODE: __ENV.MODE || 'unknown', // only used in the result file name
};

// 409 (insufficient stock) is a correct business outcome, not a failure, so http_req_failed
// only counts real errors: 5xx (incl. 503 contention), timeouts, connection errors.
http.setResponseCallback(http.expectedStatuses(201, 409));

export const ordersAllocated = new Counter('orders_allocated');   // 201
export const ordersRejected = new Counter('orders_rejected');     // 409
export const ordersContention = new Counter('orders_contention'); // 503

export const options = {
  vus: cfg.VUS,
  duration: cfg.DURATION,
  // the default summary has no p(99)
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function createItem(quantityAvailable) {
  const res = http.post(
    `${cfg.BASE_URL}/api/inventory`,
    JSON.stringify({ quantityAvailable }),
    { headers: JSON_HEADERS },
  );
  if (res.status !== 201) {
    throw new Error(`setup: creating an item failed with status ${res.status}: ${res.body}`);
  }
  return res.json('itemId');
}

export function placeOrder(itemId) {
  // Unique key per request, so every call is a new order (no idempotent replays).
  const key = `${__VU}-${__ITER}-${Date.now()}`;
  const res = http.post(
    `${cfg.BASE_URL}/api/orders`,
    JSON.stringify({ itemId, quantityRequested: cfg.QTY }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': key } },
  );

  check(res, { 'status is 201 or 409': (r) => r.status === 201 || r.status === 409 });

  // Always record all three (0 or 1) so each counter exists in the summary even if it never fires.
  ordersAllocated.add(res.status === 201 ? 1 : 0);
  ordersRejected.add(res.status === 409 ? 1 : 0);
  ordersContention.add(res.status === 503 ? 1 : 0);
}

// <script>-<mode>-<vus>vus-<timestamp>.json under load-tests/results/, plus the normal text summary.
export function makeSummary(scriptName, data) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const file = `/scripts/results/${scriptName}-${cfg.MODE}-${cfg.VUS}vus-${stamp}.json`;
  return {
    [file]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: false }),
  };
}
