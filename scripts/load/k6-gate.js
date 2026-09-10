// Dargent M5 S3 — k6 HARD GATE on the money path (D4 adjudication).
//
// The E16 honest baseline (37.38ms create p95, spine ON) seeds a measured-regression tripwire:
//   create p95 < 75ms  (2x the honest 37.38ms; generous vs the 250ms SLO — trips on REGRESSION,
//                       not on absolute SLO breach)
//   http_req_failed == 0 (zero-tolerance: any non-2xx on the money path is a red build)
//   suite walls: replay < 250ms, pay < 250ms, confirm < 100ms, checks rate > 0.99
//
// Gate topology = BASE compose (spine OFF — the smoke topology): the spine's ~20ms reconciler
// cost is a different concern (see load-test-baseline.md); this gate measures the synchronous
// money path a client pays for on every request.
//
// Profile: 8 VUs, 15s ramp -> 45s steady (~1m load) — sized to the CI runner budget. NOT the
// E16 24-VU consultative baseline: the gate must fit inside one job beside its own bite-proof.
//
// BITE-PROOF (mandatory, same job): DARGENT_K6_GATE_BITE=1 rewrites the create threshold to
// p(95)<1ms — an impossible bar. The gate MUST exit red then (a gate that cannot be shown
// biting is not a gate; the red run is evidence, not a flake to retry).
//
// Env (DARGENT_* only):
//   DARGENT_K6_API_KEY    raw API key (inserted by the harness, ci-k6-gate.sh)
//   DARGENT_K6_GATE_BITE  "1" -> intentionally-impossible threshold (bite proof; never default)

import http from 'k6/http';
import { check, sleep } from 'k6';

const BITE = __ENV.DARGENT_K6_GATE_BITE === '1';

export const options = {
  stages: [
    { duration: '15s', target: 8 },
    { duration: '45s', target: 8 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'], // catch-all wall
    // D4 tripwire: 2x the honest p95 (37.38ms). Bite mode rewrites this to an impossible bar.
    [`http_req_duration{name:create}`]: [BITE ? 'p(95)<1' : 'p(95)<75'],
    'http_req_duration{name:replay}': ['p(95)<250'],
    'http_req_duration{name:pay}': ['p(95)<250'],
    'http_req_duration{name:confirm}': ['p(95)<100'],
    http_req_failed: ['rate==0'], // 0-tolerance (D4): any money-path HTTP error is red
    checks: ['rate>0.99'],
  },
};

const API = __ENV.DARGENT_K6_API_BASE || 'http://localhost:8080';
const PSP = __ENV.DARGENT_K6_PSP_BASE || 'http://localhost:8090';
const API_KEY = __ENV.DARGENT_K6_API_KEY;
if (!API_KEY) throw new Error('DARGENT_K6_API_KEY is required');

const headers = { 'Authorization': `Bearer ${API_KEY}`, 'Content-Type': 'application/json' };

export default function () {
  const key = `gate-${__VU}-${Date.now()}-${__ITER}`;
  const body = JSON.stringify({ amount: 100, description: `k6 gate VU${__VU}` });
  const reqHeaders = { ...headers, 'Idempotency-Key': key };

  // create — the measured tripwire leg
  const create = http.post(`${API}/v1/payments`, body, { headers: reqHeaders, tags: { name: 'create' } });
  check(create, { 'create 201': (r) => r.status === 201 });
  if (create.status !== 201) return; // 0-tolerance threshold fails the build
  const txid = (create.body.match(/"txid":"([^"]+)"/) || [])[1];
  check(txid, { 'create has txid': (t) => !!t });
  if (!txid) return;

  // idempotent replay — same key + body (also exercises the S2 replay cache read path)
  const replay = http.post(`${API}/v1/payments`, body, { headers: reqHeaders, tags: { name: 'replay' } });
  check(replay, { 'replay 201 + Idempotent-Replay': (r) => r.status === 201 && r.headers['Idempotent-Replay'] === 'true' });

  // pay at the simulator (fires the signed webhook -> confirm via intake, base topology)
  const pay = http.post(`${PSP}/cobs/${txid}/payments`, null, { tags: { name: 'pay' } });
  check(pay, { 'pay 200': (r) => r.status === 200 });

  // poll until CONFIRMED (deadline generous vs the webhook path; wall asserted on GET p95)
  const deadline = Date.now() + 30_000;
  let confirmed = false;
  while (Date.now() < deadline) {
    const r = http.get(`${API}/v1/payments/${txid}`, { headers, tags: { name: 'confirm' } });
    check(r, { 'confirm GET 200/404': (res) => res.status === 200 || res.status === 404 });
    if (r.status !== 200 && r.status !== 404) break; // 0-tolerance threshold catches it
    if (r.body && r.body.includes('"status":"CONFIRMED"')) { confirmed = true; break; }
    sleep(0.05);
  }
  check(confirmed, { 'confirm CONFIRMED within deadline': (c) => c === true });
  sleep(0.2);
}