// Dargent E15 S3 — k6 money-path baseline.
//
// Flow per iteration (exactly the smoke contract, minus expiry masking):
//   create  POST {api}/v1/payments           -> 201 PENDING + txid      (SLO p95 < 250ms)
//   replay  POST {api}/v1/payments (same)    -> 201, Idempotent-Replay  (SLO p95 < 250ms)
//   pay     POST {psp}/cobs/{txid}/payments  -> 200 (simulator marks paid; fires signed webhook)
//   confirm poll GET {api}/v1/payments/{txid}-> 200 CONFIRMED           (SLO p95 < 100ms)
//
// Consultative — NOT a CI gate (ops-e15-spec §S3.2). Thresholds are asserted so the run FAILS
// loudly if a machine drift ever exceeds SLO even before we read the doc. Webhook SLO (p95<150ms)
// is server-side (simulator->api via NGINX) and surfaced by the run's `_psp_to_api_roundtrip`
// tag on the CREATE-echo of confirm poll, not asserted by k6.
//
// Env (DARGENT_* only; URL sits in the run command):
//   DARGENT_K6_API_KEY   raw API key; inserted in the DB as psp_test_ prefix (see baseline doc)
//
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

export const options = {
  // Q-batch decision (2026-09-08): 24 VUs, 30s ramp -> 2m steady. Chosen so the run completes
  // inside the compose smoke window and covers a full webhook->confirm roundtrip per VU.
  stages: [
    { duration: '30s', target: 24 },
    { duration: '2m', target: 24 },
  ],
  thresholds: {
    // E12/SLO buckets — assert here so the gate is the test itself.
    http_req_duration: ['p(95)<1000'],              // catch-all wall
    'http_req_duration{name:create}': ['p(95)<250'],
    'http_req_duration{name:replay}': ['p(95)<250'],
    'http_req_duration{name:pay}': ['p(95)<250'],
    'http_req_duration{name:confirm}': ['p(95)<100'],
    checks: ['rate>0.99'],
  },
};

const API = __ENV.DARGENT_K6_API_BASE || 'http://localhost:8080';
const PSP = __ENV.DARGENT_K6_PSP_BASE || 'http://localhost:8090';
const API_KEY = __ENV.DARGENT_K6_API_KEY;
if (!API_KEY) throw new Error('DARGENT_K6_API_KEY is required');

const headers = { 'Authorization': `Bearer ${API_KEY}`, 'Content-Type': 'application/json' };
const confirmErr = new Rate('confirm_errors');
const confirmTrend = new Trend('confirm_roundtrip_ms', true);

function confirmPayment(txid) {
  const deadline = Date.now() + 90_000; // webhook path SLO gives far under this
  const start = Date.now();
  let body = '';
  while (Date.now() < deadline) {
    const r = http.get(`${API}/v1/payments/${txid}`, { headers, tags: { name: 'confirm' } });
    check(r, { 'confirm GET http 200/404': (res) => res.status === 200 || res.status === 404 });
    if (r.status !== 200 && r.status !== 404) { confirmErr.add(1); sleep(1); continue; }
    body = r.body;
    if (body.includes('"status":"CONFIRMED"')) { confirmTrend.add(Date.now() - start); return true; }
    sleep(0.05);
  }
  confirmErr.add(1);
  return false;
}

export default function () {
  const key = `k6-${__VU}-${Date.now()}-${__ITER}`;
  const body = JSON.stringify({ amount: 100, description: `k6 baseline VU${__VU}` });
  const reqHeaders = { ...headers, 'Idempotency-Key': key };

  // create
  const create = http.post(`${API}/v1/payments`, body, { headers: reqHeaders, tags: { name: 'create' } });
  check(create, { 'create 201': (r) => r.status === 201 });
  if (create.status !== 201) return; // threshold + check rate will fail loudly
  const txid = (create.body.match(/"txid":"([^"]+)"/) || [])[1];
  check(txid, { 'create has txid': (t) => !!t });
  if (!txid) return;

  // idempotent replay (same body + key -> byte-equal snapshot modulo expiresIn, BD-6)
  const replay = http.post(`${API}/v1/payments`, body, { headers: reqHeaders, tags: { name: 'replay' } });
  check(replay, { 'replay 201 + Idempotent-Replay': (r) => r.status === 201 && r.headers['Idempotent-Replay'] === 'true' });

  // pay at simulator (fires signed webhook -> api intake)
  const pay = http.post(`${PSP}/cobs/${txid}/payments`, null, { tags: { name: 'pay' } });
  check(pay, { 'pay 200': (r) => r.status === 200 });

  // confirm via webhook -> poll
  const confirmed = confirmPayment(txid);
  check(confirmed, { 'confirm CONFIRMED within deadline': (c) => c === true });
  sleep(0.2);
}