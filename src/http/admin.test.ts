import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresBridgeRepository } from '../db/bridges';
import { PostgresAuthService } from '../domains/auth';
import { PostgresAdminService } from '../domains/admin';
import { PostgresLetterService } from '../domains/letters';
import { PostgresSurveyService } from '../domains/survey';
import { totp } from '../domains/totp';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;
const origin = 'http://localhost:5173';

describeWithDb('admin question API', () => {
  let pool: Pool;
  let client: PoolClient;
  let auth: PostgresAuthService;
  let app: ReturnType<typeof createApp>;
  let cookie: string;
  let csrf: string;
  const schema = `test_${randomUUID().replaceAll('-', '')}`;
  const directory = fileURLToPath(new URL('../../migrations/', import.meta.url));
  const now = new Date('2026-10-03T06:00:00Z');

  beforeAll(async () => {
    pool = new Pool({ connectionString: databaseUrl });
    client = await pool.connect();
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await applyMigrations(client, directory);
    auth = new PostgresAuthService(client, Buffer.alloc(32, 9));
    app = createApp({ auth, admin: new PostgresAdminService(client), bridges: new PostgresBridgeRepository(client),
      letters: new PostgresLetterService(client),
      survey: new PostgresSurveyService(client),
      allowedOrigins: [origin], secureCookies: false, now: () => now });
    const provisioned = await auth.provisionAdmin('질문운영자', 'admin-password-123');
    const response = await app(new Request('http://localhost/api/v1/admin/auth/login', {
      method: 'POST', headers: { origin, 'content-type': 'application/json' },
      body: JSON.stringify({ nickname: '질문운영자', password: 'admin-password-123', totp: totp(provisioned.totpSecret, new Date()) }),
    }));
    expect(response.status).toBe(200);
    cookie = response.headers.get('set-cookie')!.split(';')[0];
    csrf = ((await response.json()) as { data: { csrfToken: string } }).data.csrfToken;
  });

  afterAll(async () => {
    if (client) {
      await client.query('SET search_path TO public');
      await client.query(`DROP SCHEMA ${schema} CASCADE`);
      client.release();
    }
    if (pool) await pool.end();
  });

  const send = (app: ReturnType<typeof createApp>, path: string, method: string, body: unknown, headers: Record<string, string> = {}) =>
    app(new Request(`http://localhost${path}`, { method, headers: { origin, 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) }));

  it('schedules a question for KST Monday and keeps the previous question until then', async () => {
    const first = await send(app, '/api/v1/admin/questions', 'POST', { bridgeId: 'sillim', body: '이번 주 질문' }, { cookie, 'x-csrf-token': csrf });
    expect(first.status).toBe(201);
    const next = await send(app, '/api/v1/admin/questions', 'POST', {
      bridgeId: 'sillim', body: '다음 주 질문', effectiveAt: '2026-10-04T15:00:00.000Z',
    }, { cookie, 'x-csrf-token': csrf });
    expect(next.status).toBe(201);
    const nextId = ((await next.json()) as { data: { id: string } }).data.id;
    const before = await app(new Request('http://localhost/api/v1/bridges/sillim'));
    expect(((await before.json()) as { data: { currentQuestion: { body: string } } }).data.currentQuestion.body).toBe('이번 주 질문');

    const corrected = await send(app, `/api/v1/admin/questions/${nextId}`, 'PATCH', { body: '수정된 다음 주 질문' }, { cookie, 'x-csrf-token': csrf });
    expect(corrected.status).toBe(200);
    const afterApp = createApp({ bridges: new PostgresBridgeRepository(client), now: () => new Date('2026-10-04T15:00:00Z') });
    const after = await afterApp(new Request('http://localhost/api/v1/bridges/sillim'));
    expect(((await after.json()) as { data: { currentQuestion: { id: string; body: string } } }).data.currentQuestion)
      .toMatchObject({ id: nextId, body: '수정된 다음 주 질문' });
    const audit = await client.query('SELECT action FROM admin_actions ORDER BY created_at');
    expect(audit.rows.map((row) => row.action)).toEqual(['question.create', 'question.create', 'question.correct']);
  });

  it('rejects missing admin session, wrong CSRF, and a non-Monday schedule', async () => {
    const input = { bridgeId: 'bongnim', body: '예약 질문', effectiveAt: '2026-10-05T15:00:00.000Z' };
    expect((await send(app, '/api/v1/admin/questions', 'POST', input)).status).toBe(401);
    expect((await send(app, '/api/v1/admin/questions', 'POST', input, { cookie })).status).toBe(403);
    expect((await send(app, '/api/v1/admin/questions', 'POST', input, { cookie, 'x-csrf-token': csrf })).status).toBe(422);
  });

  it('lists each bridge current and next-week question and includes hidden start letters', async () => {
    const currentId = randomUUID();
    const scheduledId = randomUUID();
    await client.query(`INSERT INTO questions (id, bridge_id, body, effective_at, status)
      VALUES ($1, 'bongnim', '조회용 현재 질문', '2026-09-27T15:00:00Z', 'published'),
             ($2, 'bongnim', '조회용 다음 질문', '2026-10-04T15:00:00Z', 'published')`,
    [currentId, scheduledId]);
    const questions = await app(new Request('http://localhost/api/v1/admin/questions', { headers: { cookie } }));
    expect(questions.status).toBe(200);
    const questionData = (await questions.json()) as { data: Array<{
      bridgeId: string; currentQuestion: { id: string } | null; scheduledQuestions: Array<{ id: string }>;
      nextWeekMissing: boolean;
    }> };
    expect(questionData.data).toHaveLength(3);
    expect(questionData.data.find((row) => row.bridgeId === 'bongnim')).toMatchObject({
      currentQuestion: { id: currentId }, scheduledQuestions: [{ id: scheduledId }], nextWeekMissing: false,
    });
    expect(questionData.data.find((row) => row.bridgeId === 'sillim2')?.nextWeekMissing).toBe(true);
    expect((await app(new Request('http://localhost/api/v1/admin/questions'))).status).toBe(401);

    const seed = await send(app, '/api/v1/admin/seeds', 'POST', {
      bridgeId: 'sillim2', body: '당신을 응원해요.', theme: 1, displayNickname: '별빛안부',
    }, { cookie, 'x-csrf-token': csrf });
    const seedId = ((await seed.json()) as { data: { id: string } }).data.id;
    expect((await send(app, `/api/v1/admin/letters/${seedId}/review`, 'POST',
      { decision: 'hide', reason: '시작 편지 잠시 비공개' }, { cookie, 'x-csrf-token': csrf })).status).toBe(200);
    const seeds = await app(new Request('http://localhost/api/v1/admin/seeds?bridgeId=sillim2', { headers: { cookie } }));
    expect(seeds.status).toBe(200);
    expect(((await seeds.json()) as { data: Array<{ id: string; status: string }> }).data)
      .toContainEqual(expect.objectContaining({ id: seedId, status: 'hidden' }));
  });

  it('shows the survey link only during the configured experiment window', async () => {
    const invalid = await send(app, '/api/v1/admin/survey', 'PUT', {
      url: 'http://unsafe.example/form', startsAt: '2026-10-04T15:00:00.000Z',
      endsAt: '2026-10-06T15:00:00.000Z', enabled: true, reason: '실험 준비',
    }, { cookie, 'x-csrf-token': csrf });
    expect(invalid.status).toBe(422);
    const impossibleDate = await send(app, '/api/v1/admin/survey', 'PUT', {
      url: 'https://survey.example/form', startsAt: '2026-02-30T00:00:00.000Z',
      endsAt: '2026-03-05T00:00:00.000Z', enabled: true, reason: '실험 준비',
    }, { cookie, 'x-csrf-token': csrf });
    expect(impossibleDate.status).toBe(422);
    const configured = await send(app, '/api/v1/admin/survey', 'PUT', {
      url: 'https://survey.example/form', startsAt: '2026-10-04T15:00:00.000Z',
      endsAt: '2026-10-06T15:00:00.000Z', enabled: true, reason: '실험 일정 확정',
    }, { cookie, 'x-csrf-token': csrf });
    expect(configured.status).toBe(200);
    expect((await app(new Request('http://localhost/api/v1/admin/survey'))).status).toBe(401);
    const before = await app(new Request('http://localhost/api/v1/survey'));
    expect((await before.json()) as { data: unknown }).toEqual({ data: { active: false } });
    const duringApp = createApp({ survey: new PostgresSurveyService(client), now: () => new Date('2026-10-04T15:00:00Z') });
    const during = await duringApp(new Request('http://localhost/api/v1/survey'));
    expect((await during.json()) as { data: unknown }).toEqual({ data: { active: true, url: 'https://survey.example/form' } });
    const afterApp = createApp({ survey: new PostgresSurveyService(client), now: () => new Date('2026-10-06T15:00:00Z') });
    const after = await afterApp(new Request('http://localhost/api/v1/survey'));
    expect((await after.json()) as { data: unknown }).toEqual({ data: { active: false } });
  });

  it('publishes a start letter, reviews a reported letter, and resolves a private inquiry', async () => {
    const seed = await send(app, '/api/v1/admin/seeds', 'POST', {
      bridgeId: 'bongnim', body: '오늘도 잘 버텨 왔어요.', theme: 1, displayNickname: '별빛안부',
    }, { cookie, 'x-csrf-token': csrf });
    expect(seed.status).toBe(201);
    const letterId = ((await seed.json()) as { data: { id: string } }).data.id;
    const river = await app(new Request('http://localhost/api/v1/bridges/bongnim/river'));
    expect(river.status).toBe(200);
    expect(((await river.json()) as { data: Array<{ id: string }> }).data).toContainEqual(expect.objectContaining({ id: letterId }));
    const edit = await send(app, `/api/v1/admin/seeds/${letterId}`, 'PATCH', {
      body: '오늘도 고생했어요.', theme: 3, displayNickname: '별빛안부',
    }, { cookie, 'x-csrf-token': csrf });
    expect(edit.status).toBe(200);
    const detail = await app(new Request(`http://localhost/api/v1/letters/${letterId}`));
    expect(((await detail.json()) as { data: { body: string; theme: number } }).data)
      .toMatchObject({ body: '오늘도 고생했어요.', theme: 3 });

    const inquiry = await client.query<{ id: string }>(
      `INSERT INTO inquiries (id, type, body, related_letter_id, status)
       VALUES ($1, 'privacy', '개인정보가 보여요', $2, 'open') RETURNING id`, [randomUUID(), letterId],
    );
    const firstReport = randomUUID();
    await client.query(
      `INSERT INTO reports (id, letter_id, round, reason_code, status)
       VALUES ($1, $2, 1, 'personal_info', 'pending')`, [firstReport, letterId],
    );
    const reports = await app(new Request('http://localhost/api/v1/admin/reports', { headers: { cookie } }));
    expect(reports.status).toBe(200);
    expect(((await reports.json()) as { data: Array<{ letterId: string; body: string }> }).data)
      .toContainEqual(expect.objectContaining({ letterId, body: '오늘도 고생했어요.' }));
    const inbox = await app(new Request('http://localhost/api/v1/admin/inquiries', { headers: { cookie } }));
    expect(inbox.status).toBe(200);
    expect(((await inbox.json()) as { data: Array<{ id: string; body: string }> }).data)
      .toContainEqual(expect.objectContaining({ id: inquiry.rows[0].id, body: '개인정보가 보여요' }));
    const alerts = await app(new Request('http://localhost/api/v1/admin/alerts', { headers: { cookie } }));
    expect(alerts.status).toBe(200);
    expect(((await alerts.json()) as { data: { privacyInquiries: number; pendingReportLetters: number } }).data)
      .toMatchObject({ privacyInquiries: 1, pendingReportLetters: 1 });

    const hide = await send(app, `/api/v1/admin/letters/${letterId}/review`, 'POST',
      { decision: 'hide', reason: '개인정보 확인 중' }, { cookie, 'x-csrf-token': csrf });
    expect(hide.status).toBe(200);
    const hidden = await client.query('SELECT status FROM letters WHERE id = $1', [letterId]);
    expect(hidden.rows[0].status).toBe('hidden');
    const keepHidden = await send(app, `/api/v1/admin/letters/${letterId}/review`, 'POST',
      { decision: 'keep_hidden', reason: '운영 정책 위반 확인' }, { cookie, 'x-csrf-token': csrf });
    expect(keepHidden.status).toBe(200);
    const reportStatus = await client.query('SELECT status FROM reports WHERE id = $1', [firstReport]);
    expect(reportStatus.rows[0].status).toBe('upheld');
    const reviewedAlerts = await app(new Request('http://localhost/api/v1/admin/alerts', { headers: { cookie } }));
    expect(((await reviewedAlerts.json()) as { data: { pendingReportLetters: number } }).data.pendingReportLetters).toBe(0);
    const restore = await send(app, `/api/v1/admin/letters/${letterId}/review`, 'POST',
      { decision: 'restore', reason: '개인정보 없음 확인' }, { cookie, 'x-csrf-token': csrf });
    expect(restore.status).toBe(200);
    const round = await client.query('SELECT status, report_round FROM letters WHERE id = $1', [letterId]);
    expect(round.rows[0]).toMatchObject({ status: 'published', report_round: 2 });

    const resolve = await send(app, `/api/v1/admin/inquiries/${inquiry.rows[0].id}`, 'PATCH',
      { status: 'resolved', reason: '확인 후 처리' }, { cookie, 'x-csrf-token': csrf });
    expect(resolve.status).toBe(200);
    const inquiryStatus = await client.query('SELECT status FROM inquiries WHERE id = $1', [inquiry.rows[0].id]);
    expect(inquiryStatus.rows[0].status).toBe('resolved');
  });

  it('permanently removes a reviewed letter and its engagement while retaining a deletion record', async () => {
    const seed = await send(app, '/api/v1/admin/seeds', 'POST', {
      bridgeId: 'sillim2', body: '여기에서 당신을 응원해요.', theme: 2, displayNickname: '별빛안부',
    }, { cookie, 'x-csrf-token': csrf });
    const letterId = ((await seed.json()) as { data: { id: string } }).data.id;
    const member = await auth.signup('삭제검증회원', 'member-password-123');
    await client.query(`INSERT INTO reactions (id, letter_id, user_id, active, activated_at)
      VALUES ($1, $2, $3, true, now())`, [randomUUID(), letterId, member.user.id]);
    await client.query('INSERT INTO saves (user_id, letter_id) VALUES ($1, $2)', [member.user.id, letterId]);

    const removed = await send(app, `/api/v1/admin/letters/${letterId}/review`, 'POST',
      { decision: 'delete', reason: '개인정보 노출 확인' }, { cookie, 'x-csrf-token': csrf });
    expect(removed.status).toBe(200);
    const letter = await client.query('SELECT status, body, display_nickname FROM letters WHERE id = $1', [letterId]);
    expect(letter.rows[0]).toEqual({ status: 'deleted', body: null, display_nickname: null });
    expect((await client.query('SELECT id FROM reactions WHERE letter_id = $1', [letterId])).rows).toHaveLength(0);
    expect((await client.query('SELECT letter_id FROM saves WHERE letter_id = $1', [letterId])).rows).toHaveLength(0);
    expect((await client.query('SELECT id FROM deletion_events WHERE letter_id = $1', [letterId])).rows).toHaveLength(1);
    expect((await app(new Request(`http://localhost/api/v1/letters/${letterId}`))).status).toBe(404);
  });

  it('shows requester and author IDs for an owner removal inquiry', async () => {
    const member = await auth.signup('제거요청회원', 'member-password-123');
    const questionId = randomUUID();
    const letterId = randomUUID();
    const inquiryId = randomUUID();
    await client.query(`INSERT INTO questions (id, bridge_id, body, effective_at, status)
      VALUES ($1, 'sillim2', '질문', $2, 'published')`, [questionId, now]);
    await client.query(`INSERT INTO letters
      (id, bridge_id, question_id, author_user_id, display_nickname, body, theme, is_seed, status)
      VALUES ($1, 'sillim2', $2, $3, '제거요청회원', '다시 볼 편지', 1, false, 'published')`,
    [letterId, questionId, member.user.id]);
    await client.query(`INSERT INTO inquiries (id, type, body, related_letter_id, requester_user_id, status)
      VALUES ($1, 'removal', '제가 쓴 편지를 제거해 주세요', $2, $3, 'open')`, [inquiryId, letterId, member.user.id]);
    const inbox = await app(new Request('http://localhost/api/v1/admin/inquiries', { headers: { cookie } }));
    expect(((await inbox.json()) as { data: Array<{ id: string; requesterUserId: string; relatedLetterAuthorUserId: string }> }).data)
      .toContainEqual(expect.objectContaining({ id: inquiryId, requesterUserId: member.user.id,
        relatedLetterAuthorUserId: member.user.id }));
  });

  it('flags reports from recently created accounts for operator review', async () => {
    const letterId = randomUUID();
    const recent = randomUUID();
    const established = randomUUID();
    await client.query(`INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status)
      VALUES ($1, 'sillim', '별빛안부', '응원합니다', 1, true, 'published')`, [letterId]);
    await client.query(`INSERT INTO users
      (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status, created_at)
      VALUES ($1, '최근신고자', '최근신고자', 'hash', 'recovery', 'member', 'active', now() - interval '1 hour'),
             ($2, '오래된신고자', '오래된신고자', 'hash', 'recovery', 'member', 'active', now() - interval '30 days')`,
    [recent, established]);
    await client.query(`INSERT INTO reports (id, letter_id, reporter_user_id, round, reason_code, status)
      VALUES ($1, $3, $4, 1, 'other', 'pending'), ($2, $3, $5, 1, 'other', 'pending')`,
    [randomUUID(), randomUUID(), letterId, recent, established]);
    const response = await app(new Request('http://localhost/api/v1/admin/reports', { headers: { cookie } }));
    expect(((await response.json()) as { data: Array<{ letterId: string; reportCount: number; newReporterCount: number }> }).data)
      .toContainEqual(expect.objectContaining({ letterId, reportCount: 2, newReporterCount: 1 }));
  });

  it('lets an admin activate and disable a literal content rule without redeploying', async () => {
    const questionId = randomUUID();
    await client.query(`INSERT INTO questions (id, bridge_id, body, effective_at, status)
      VALUES ($1, 'bongnim', '오늘의 질문', $2, 'published')`, [questionId, now]);
    const member = await auth.signup('검사규칙회원', 'member-password-123');
    const memberCookie = `star_session_dev=${member.sessionToken}`;
    const write = (key: string) => send(app, '/api/v1/bridges/bongnim/letters', 'POST', {
      questionId, body: '경품 홍보라는 말이 자꾸 떠올라요.', theme: 1,
    }, { cookie: memberCookie, 'x-csrf-token': member.csrfToken, 'idempotency-key': key });
    const created = await send(app, '/api/v1/admin/moderation-rules', 'POST', {
      category: 'advertising', phrase: '경품 홍보', reason: '새 광고 문구 차단',
    }, { cookie, 'x-csrf-token': csrf });
    expect(created.status).toBe(201);
    const ruleId = ((await created.json()) as { data: { id: string } }).data.id;
    expect((await write(randomUUID())).status).toBe(422);
    const disabled = await send(app, `/api/v1/admin/moderation-rules/${ruleId}`, 'PATCH',
      { active: false, reason: '오탐 확인' }, { cookie, 'x-csrf-token': csrf });
    expect(disabled.status).toBe(200);
    expect((await write(randomUUID())).status).toBe(201);
  });
});
