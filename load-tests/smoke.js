// Short smoke test over BOTH flows, to put some traffic on the dashboards and metrics.
// Half the iterations use POST /api/orders (direct), half POST /api/checkout (saga). Items are small, so some
// requests run out of stock (409). Start the app with payment failures to see declines (402), timeouts (502)
// and compensations, e.g.  --payment.fake.failure-rate=0.2 --payment.fake.timeout-rate=0.05
//
//   docker run --rm -i -v "${PWD}/load-tests:/scripts" -e VUS=10 -e DURATION=20s grafana/k6 run --quiet /scripts/smoke.js
import http from 'k6/http';
import { check } from 'k6';
import { cfg, createItem } from './lib/common.js';

export const options = {
  vus: cfg.VUS,
  duration: cfg.DURATION,
  // No pass/fail thresholds: this run exists to produce traffic, and it provokes 502s on purpose.
};

// 402 (declined) and 409 (no stock) are correct answers. 502 (payment timeout) is deliberately NOT listed.
http.setResponseCallback(http.expectedStatuses(201, 402, 409));

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
  const itemIds = [];
  for (let i = 0; i < 20; i++) {
    itemIds.push(createItem(200)); // small: a 20-item catalogue runs dry during the run
  }
  return { itemIds };
}

export default function (data) {
  const itemId = data.itemIds[Math.floor(Math.random() * data.itemIds.length)];
  const key = `smoke-${__VU}-${__ITER}-${Date.now()}`;
  const path = Math.random() < 0.5 ? '/api/orders' : '/api/checkout';
  const res = http.post(
    `${cfg.BASE_URL}${path}`,
    JSON.stringify({ itemId, quantityRequested: 1 }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': key }, tags: { flow: path === '/api/orders' ? 'direct' : 'checkout' } },
  );
  check(res, { 'an expected answer (201, 402, 409, 502)': (r) => [201, 402, 409, 502].includes(r.status) });
}
