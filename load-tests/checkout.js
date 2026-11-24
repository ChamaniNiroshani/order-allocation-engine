// Checkout flow only (POST /api/checkout), used by the app-kill chaos experiment (chaos-app.ps1).
// Each iteration reserves stock, waits for the (fake) payment, then confirms or compensates. With a slow gateway
// (PAYMENT_FAKE_LATENCY_MS on the app) every VU spends its time holding one RESERVED order.
//
// 201 = confirmed, 402 = payment declined, 409 = no stock are correct answers. There are no thresholds: the chaos
// script kills the app on purpose, so connection errors are expected in that run.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { cfg, createItem } from './lib/common.js';

export const options = {
  vus: cfg.VUS,
  duration: cfg.DURATION,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

http.setResponseCallback(http.expectedStatuses(201, 402, 409));

export function setup() {
  const itemIds = [];
  for (let i = 0; i < cfg.ITEMS; i++) {
    itemIds.push(createItem(cfg.STOCK));
  }
  return { itemIds };
}

export default function (data) {
  const itemId = data.itemIds[Math.floor(Math.random() * data.itemIds.length)];
  const res = http.post(
    `${cfg.BASE_URL}/api/checkout`,
    JSON.stringify({ itemId, quantityRequested: cfg.QTY }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `co-${__VU}-${__ITER}-${Date.now()}` } },
  );
  check(res, { 'an expected answer (201, 402, 409)': (r) => [201, 402, 409].includes(r.status) });
  if (res.status === 0) {
    sleep(1); // the app is down (chaos run): do not spin on connection errors
  }
}
