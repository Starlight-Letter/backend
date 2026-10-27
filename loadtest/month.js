// 데이터 규모별 비교: 비회원 둘러보기 100명 + 회원 50명 + 운영자 지표 화면 1명, 60초
import http from 'k6/http';
import { check, sleep } from 'k6';

const API = __ENV.API || 'http://127.0.0.1:38080/api/v1';
const DUR = __ENV.DUR || '60s';
const ORIGIN = 'http://localhost:5173';
const bridges = ['bongnim', 'sillim', 'sillim2'];
const names = ['bridges', 'bridge', 'river', 'letter', 'event', 'best', 'me', 'saved', 'trace', 'mine', 'stars', 'engagement', 'reaction', 'admin_metrics', 'admin_alerts'];

export const options = {
  scenarios: {
    guests: { executor: 'ramping-vus', stages: [{ duration: '10s', target: 100 }, { duration: DUR, target: 100 }], exec: 'guest' },
    members: { executor: 'ramping-vus', stages: [{ duration: '10s', target: 50 }, { duration: DUR, target: 50 }], exec: 'member' },
    admin: { executor: 'constant-vus', vus: 1, duration: DUR, exec: 'admin' },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  // 요청 이름별 지표가 요약에 나오도록 넉넉한 임계값을 건다
  thresholds: Object.fromEntries([
    ['http_req_failed', ['rate<0.01']],
    ...names.map((n) => [`http_req_duration{name:${n}}`, ['p(95)<60000']]),
  ]),
};

const uuid = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = (Math.random() * 16) | 0; return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
});
const get = (path, name, params = {}) => http.get(`${API}${path}`, { ...params, headers: { Origin: ORIGIN, ...(params.headers || {}) }, tags: { name } });
const post = (path, body, name, headers = {}) => http.post(`${API}${path}`, JSON.stringify(body), { headers: { 'Content-Type': 'application/json', Origin: ORIGIN, ...headers }, tags: { name } });

function event(type, extra, headers) {
  check(post('/events', { eventId: uuid(), eventType: type, ...extra }, 'event', headers), { 'event 201': (r) => r.status === 201 });
}

function pickLetter(bridge, headers) {
  const river = get(`/bridges/${bridge}/river`, 'river', { headers });
  check(river, { 'river 200': (r) => r.status === 200 });
  const letters = river.status === 200 ? river.json('data') || [] : [];
  return letters.length ? letters[Math.floor(Math.random() * letters.length)].id : null;
}

export function guest() {
  const b = bridges[Math.floor(Math.random() * 3)];
  get('/bridges', 'bridges');
  event('home_visit', {});
  check(get(`/bridges/${b}`, 'bridge'), { 'bridge 200': (r) => r.status === 200 });
  event('bridge_visit', { bridgeId: b });
  const id = pickLetter(b);
  sleep(1 + Math.random() * 2);
  if (id) {
    check(get(`/letters/${id}`, 'letter'), { 'letter 200': (r) => r.status === 200 });
    event('letter_open', { letterId: id });
  }
  sleep(1 + Math.random() * 2);
  check(get(`/best?scope=all&period=${Math.random() < 0.5 ? 'week' : 'month'}`, 'best'), { 'best 200': (r) => r.status === 200 });
  sleep(1 + Math.random() * 2);
}

export function member() {
  const n = __VU % 35 + 1; // 기준 데이터의 회원 수(35)에 맞춰 세 규모 모두 같은 세션을 돌려 쓴다
  const headers = { Cookie: `star_session_dev=lt-member-${n}` };
  const csrf = `lt-csrf-${n}`.padEnd(43, 'x');
  const b = bridges[Math.floor(Math.random() * 3)];
  check(get('/me', 'me', { headers }), { 'me 200': (r) => r.status === 200 });
  const id = pickLetter(b, headers);
  sleep(1 + Math.random() * 2);
  if (id) {
    get(`/letters/${id}`, 'letter', { headers });
    check(get(`/letters/${id}/engagement`, 'engagement', { headers }), { 'engagement 200': (r) => r.status === 200 });
    const r = http.put(`${API}/letters/${id}/reaction`, null, { headers: { ...headers, Origin: ORIGIN, 'x-csrf-token': csrf }, tags: { name: 'reaction' } });
    check(r, { 'reaction 200': (x) => x.status === 200 });
  }
  sleep(1 + Math.random() * 2);
  check(get('/me/saved', 'saved', { headers }), { 'saved 200': (r) => r.status === 200 });
  check(get('/me/letter-trace', 'trace', { headers }), { 'trace 200': (r) => r.status === 200 });
  // 보관함 '내가 띄운 편지'와 계정 화면 별자리(업적 진행도 계산)
  check(get('/me/letters', 'mine', { headers }), { 'mine 200': (r) => r.status === 200 });
  check(get('/me/achievements', 'stars', { headers }), { 'stars 200': (r) => r.status === 200 });
  sleep(1 + Math.random() * 2);
}

export function admin() {
  const headers = { Cookie: 'star_session_dev=lt-admin' };
  // 운영자가 지표 탭을 기간 없이(전체) 열거나 새로고침하는 상황
  check(get('/admin/metrics', 'admin_metrics', { headers }), { 'metrics 200': (r) => r.status === 200 });
  check(get('/admin/alerts', 'admin_alerts', { headers }), { 'alerts 200': (r) => r.status === 200 });
  sleep(3);
}

export function handleSummary(data) {
  const rows = names.map((n) => {
    const m = data.metrics[`http_req_duration{name:${n}}`];
    return m ? `${n.padEnd(14)} avg=${m.values.avg.toFixed(1).padStart(8)} p95=${m.values['p(95)'].toFixed(1).padStart(8)} p99=${m.values['p(99)'].toFixed(1).padStart(8)} max=${m.values.max.toFixed(1).padStart(8)}` : `${n} -`;
  });
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values.rate;
  const checks = data.metrics.checks.values;
  const text = [`요청 ${reqs.count}건, ${reqs.rate.toFixed(1)} req/s, 실패율 ${(failed * 100).toFixed(2)}%, 체크 통과 ${(checks.rate * 100).toFixed(2)}%`, ...rows].join('\n') + '\n';
  return { stdout: text, [`${__ENV.OUT || 'summary'}.json`]: JSON.stringify(data) };
}
