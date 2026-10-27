// 쓰기 경로 부하: 회원 35명이 QR 통행권을 받아 편지를 계속 띄우고(하루 한도에 닿으면 429가 정상), 가입 화면 닉네임 확인을 반복한다.
// month.js(읽기 위주)와 같은 시간에 함께 돌려 섞인 부하를 만든다: k6 run month.js & k6 run write.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const API = __ENV.API || 'http://127.0.0.1:38080/api/v1';
const DUR = __ENV.DUR || '60s';
const ORIGIN = 'http://localhost:5173';
// seed.sql이 만드는 다리별 현재 질문
const questions = { bongnim: '00000000-0000-0000-0002-000000000001', sillim: '00000000-0000-0000-0002-000000000002', sillim2: '00000000-0000-0000-0002-000000000003' };
const bridges = Object.keys(questions);
const names = ['pass', 'write', 'nickname_ok', 'nickname_blocked'];

export const options = {
  scenarios: {
    writers: { executor: 'constant-vus', vus: 35, duration: DUR, exec: 'writer' },
    nicknames: { executor: 'constant-vus', vus: 10, duration: DUR, exec: 'nickname' },
  },
  // 실제 브라우저처럼 반복 사이에 쿠키(QR 통행권)를 유지한다. 기본값이면 반복마다 쿠키가 지워져 두 번째 편지부터 BRIDGE_LOCKED가 난다
  noCookiesReset: true,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: Object.fromEntries(names.map((n) => [`http_req_duration{name:${n}}`, ['p(95)<60000']])),
};
// 하루 작성 한도(429)는 정상 응답으로 본다
http.setResponseCallback(http.expectedStatuses(200, 201, 429));

// QR 주소의 열쇠는 운영자 API로 읽는다(seed.sql의 운영자 세션 lt-admin)
export function setup() {
  const res = http.get(`${API}/admin/bridge-qr`, { cookies: { star_session_dev: 'lt-admin' }, headers: { Origin: ORIGIN } });
  return Object.fromEntries(res.json('data').map((b) => [b.bridgeId, b.qrKey]));
}

export function writer(keys) {
  const n = __VU % 35 + 1;
  const csrf = `lt-csrf-${n}`.padEnd(43, 'x');
  const bridge = bridges[__VU % 3];
  const cookies = { star_session_dev: `lt-member-${n}` };
  if (__ITER === 0) {
    // 다리 포스터 QR을 찍은 것처럼 통행권 쿠키를 받는다(VU마다 쿠키 저장소가 따로라 다리가 섞이지 않는다)
    const pass = http.post(`${API}/bridges/${bridge}/pass`, JSON.stringify({ key: keys[bridge] }), { headers: { 'Content-Type': 'application/json', Origin: ORIGIN }, cookies, tags: { name: 'pass' } });
    check(pass, { 'pass 200': (r) => r.status === 200 });
  }
  const theme = [1, 2][__ITER % 2];
  const res = http.post(`${API}/bridges/${bridge}/letters`,
    JSON.stringify({ questionId: questions[bridge], body: `부하 테스트 편지 ${__VU}-${__ITER}. 오늘도 수고했어요.`, theme, anonymous: __ITER % 3 === 0 }),
    { headers: { 'Content-Type': 'application/json', Origin: ORIGIN, 'x-csrf-token': csrf, 'idempotency-key': `lt-${Date.now()}-${__VU}-${__ITER}` }, cookies, tags: { name: 'write' } });
  check(res, { 'write 201 or limit 429': (r) => r.status === 201 || (r.status === 429 && r.json('error.code') === 'LETTER_LIMIT') });
  sleep(1 + Math.random() * 2);
}

export function nickname() {
  // 쓸 수 있는 닉네임과 막혀야 하는 닉네임(사칭·띄어쓰기 우회)을 번갈아 확인한다
  const ok = http.get(`${API}/auth/check-nickname?nickname=${encodeURIComponent(`밤산책${__VU}${__ITER}`)}`, { headers: { Origin: ORIGIN }, tags: { name: 'nickname_ok' } });
  check(ok, { 'nickname ok': (r) => r.status === 200 && r.json('data.available') === true });
  const blocked = http.get(`${API}/auth/check-nickname?nickname=${encodeURIComponent('운 영 자')}`, { headers: { Origin: ORIGIN }, tags: { name: 'nickname_blocked' } });
  check(blocked, { 'nickname blocked': (r) => r.status === 200 && r.json('data.reason') === 'not_allowed' });
  sleep(0.5 + Math.random());
}

export function handleSummary(data) {
  const rows = names.map((n) => {
    const m = data.metrics[`http_req_duration{name:${n}}`];
    return m ? `${n.padEnd(17)} avg=${m.values.avg.toFixed(1).padStart(8)} p95=${m.values['p(95)'].toFixed(1).padStart(8)} p99=${m.values['p(99)'].toFixed(1).padStart(8)} max=${m.values.max.toFixed(1).padStart(8)}` : `${n} -`;
  });
  const checks = [];
  const walk = (g) => { g.checks.forEach((c) => checks.push(`  ${c.name}: 통과 ${c.passes}, 실패 ${c.fails}`)); g.groups.forEach(walk); };
  walk(data.root_group);
  const reqs = data.metrics.http_reqs.values;
  return { stdout: [`[쓰기] 요청 ${reqs.count}건, ${reqs.rate.toFixed(1)} req/s, 실패율 ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%`, ...rows, ...checks].join('\n') + '\n', [`${__ENV.OUT || 'write'}.json`]: JSON.stringify(data) };
}
