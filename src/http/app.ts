import { randomUUID } from 'node:crypto';
import type { BridgeReader } from '../db/bridges';
import { AuthError, type PostgresAuthService } from '../domains/auth';
import { LetterError, type PostgresLetterService } from '../domains/letters';
import { EngagementError, type PostgresEngagementService } from '../domains/engagement';
import { ReportError, type PostgresReportService } from '../domains/reports';
import { InquiryError, type PostgresInquiryService } from '../domains/inquiries';
import { AdminError, type PostgresAdminService } from '../domains/admin';
import { MetricsError, type PostgresMetricsService } from '../domains/metrics';

const MAX_BODY_BYTES = 16 * 1024;

type HealthHook = () => void | Promise<void>;

export interface AppOptions {
  onHealth?: HealthHook;
  bridges?: BridgeReader;
  auth?: PostgresAuthService;
  letters?: PostgresLetterService;
  engagement?: PostgresEngagementService;
  reports?: PostgresReportService;
  inquiries?: PostgresInquiryService;
  admin?: PostgresAdminService;
  metrics?: PostgresMetricsService;
  allowedOrigins?: string[];
  secureCookies?: boolean;
  now?: () => Date;
}

function json(status: number, value: unknown, requestId: string, origin?: string): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-request-id': requestId,
      ...(origin ? {
        'access-control-allow-origin': origin,
        'access-control-allow-credentials': 'true',
        vary: 'Origin',
      } : {}),
    },
  });
}

function payload(body: Uint8Array): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(new TextDecoder().decode(body));
    if (value && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>;
  } catch {
    throw new AuthError(400, 'INVALID_JSON', 'JSON 형식이 올바르지 않습니다.');
  }
  throw new AuthError(400, 'INVALID_REQUEST', '요청 내용을 확인해 주세요.');
}

function sessionCookie(request: Request, secure: boolean): string | null {
  const name = secure ? '__Host-star_session' : 'star_session_dev';
  const item = request.headers.get('cookie')?.split(';').map((part) => part.trim())
    .find((part) => part.startsWith(`${name}=`));
  return item?.slice(name.length + 1) ?? null;
}

function setSessionCookie(response: Response, value: string, secure: boolean, clear = false, maxAge = 2592000): void {
  const name = secure ? '__Host-star_session' : 'star_session_dev';
  response.headers.set('set-cookie',
    `${name}=${value}; Path=/; HttpOnly; SameSite=Lax${secure ? '; Secure' : ''}; Max-Age=${clear ? 0 : maxAge}`,
  );
}

async function readBody(request: Request): Promise<Uint8Array | null> {
  const declaredLength = Number(request.headers.get('content-length'));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) return null;
  if (!request.body) return new Uint8Array();

  const reader = request.body.getReader();
  let total = 0;
  const chunks: Uint8Array[] = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) return null;
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
  }
  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

export function createApp(options: AppOptions = {}) {
  return async (request: Request): Promise<Response> => {
    const requestId = randomUUID();
    const requestOrigin = request.headers.get('origin');
    const allowedOrigin = requestOrigin && options.allowedOrigins?.includes(requestOrigin) ? requestOrigin : undefined;
    const send = (status: number, value: unknown) => json(status, value, requestId, allowedOrigin);
    const secure = options.secureCookies ?? true;
    try {
      const body = await readBody(request);
      if (body === null) {
        return send(413, { error: { code: 'PAYLOAD_TOO_LARGE', message: '요청 본문이 너무 큽니다.' } });
      }
      if (body.length > 0 && request.headers.get('content-type')?.includes('application/json')) {
        try {
          JSON.parse(new TextDecoder().decode(body));
        } catch {
          return send(400, { error: { code: 'INVALID_JSON', message: 'JSON 형식이 올바르지 않습니다.' } });
        }
      }

      const path = new URL(request.url).pathname;
      if (request.method === 'OPTIONS' && requestOrigin) {
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        return new Response(null, { status: 204, headers: {
          'access-control-allow-origin': allowedOrigin,
          'access-control-allow-credentials': 'true',
          'access-control-allow-methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
          'access-control-allow-headers': 'content-type, x-csrf-token, idempotency-key',
          'x-request-id': requestId,
          vary: 'Origin',
        } });
      }
      if (request.method === 'GET' && path === '/api/v1/health') {
        await options.onHealth?.();
        return send(200, { data: { status: 'ok' } });
      }

      const authPath = path.startsWith('/api/v1/auth/') || path.startsWith('/api/v1/admin/') || path === '/api/v1/me' || path.startsWith('/api/v1/me/');
      if (authPath && !options.auth) {
        return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
      }
      if (authPath && request.method !== 'GET' && !allowedOrigin) {
        return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
      }
      const auth = options.auth;
      const clientSource = request.headers.get('x-starlight-client-ip') ?? 'unknown';
      if (auth && request.method === 'POST' && path === '/api/v1/auth/signup') {
        const input = payload(body);
        const result = await auth.signup(input.nickname, input.password, clientSource);
        const response = send(201, { data: { user: result.user, recoveryCode: result.recoveryCode, csrfToken: result.csrfToken } });
        setSessionCookie(response, result.sessionToken, secure);
        return response;
      }
      if (auth && request.method === 'POST' && path === '/api/v1/auth/login') {
        const input = payload(body);
        const result = await auth.login(input.nickname, input.password, clientSource);
        const response = send(200, { data: { user: result.user, csrfToken: result.csrfToken } });
        setSessionCookie(response, result.sessionToken, secure);
        return response;
      }
      if (auth && request.method === 'POST' && path === '/api/v1/admin/auth/login') {
        const input = payload(body);
        const result = await auth.adminLogin(input.nickname, input.password, input.totp, clientSource);
        const response = send(200, { data: { user: result.user, csrfToken: result.csrfToken } });
        setSessionCookie(response, result.sessionToken, secure, false, 43200);
        return response;
      }
      if (request.method === 'POST' && path === '/api/v1/events/survey-open') {
        if (!options.metrics) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        const input = payload(body);
        const result = await options.metrics.surveyOpen(input.eventId, input.bridgeId, clientSource);
        return send(result.counted ? 201 : 200, { data: result });
      }
      if (request.method === 'POST' && path === '/api/v1/events') {
        if (!options.metrics) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        const input = payload(body);
        const result = await options.metrics.record(input.eventId, input.eventType, input.bridgeId, input.letterId, clientSource);
        return send(result.counted ? 201 : 200, { data: result });
      }
      if (request.method === 'GET' && path === '/api/v1/admin/metrics') {
        if (!auth || !options.metrics) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        await auth.authenticateAdmin(sessionCookie(request, secure));
        return send(200, { data: await options.metrics.summary() });
      }
      const adminQuestionMatch = /^\/api\/v1\/admin\/questions\/([^/]+)$/.exec(path);
      if ((request.method === 'POST' && path === '/api/v1/admin/questions') ||
          (request.method === 'PATCH' && adminQuestionMatch)) {
        if (!auth || !options.admin) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        const actor = await auth.authorizeAdmin(sessionCookie(request, secure), request.headers.get('x-csrf-token'));
        const input = payload(body);
        const data = adminQuestionMatch
          ? await options.admin.correctQuestion(adminQuestionMatch[1], input.body, actor.id)
          : await options.admin.createQuestion(input.bridgeId, input.body, input.effectiveAt, actor.id, options.now?.() ?? new Date());
        return send(adminQuestionMatch ? 200 : 201, { data });
      }
      if (path.startsWith('/api/v1/admin/') && path !== '/api/v1/admin/auth/login') {
        if (!auth || !options.admin) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (request.method === 'GET') {
          await auth.authenticateAdmin(sessionCookie(request, secure));
          if (path === '/api/v1/admin/reports') return send(200, { data: await options.admin.listReports() });
          if (path === '/api/v1/admin/inquiries') return send(200, { data: await options.admin.listInquiries() });
          if (path === '/api/v1/admin/alerts') return send(200, { data: await options.admin.alerts() });
          if (path === '/api/v1/admin/moderation-rules') return send(200, { data: await options.admin.listModerationRules() });
        } else {
          if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
          const actor = await auth.authorizeAdmin(sessionCookie(request, secure), request.headers.get('x-csrf-token'));
          const input = payload(body);
          if (request.method === 'POST' && path === '/api/v1/admin/seeds') {
            return send(201, { data: await options.admin.createSeed(input.bridgeId, input.body, input.theme, input.displayNickname, actor.id) });
          }
          const seedMatch = /^\/api\/v1\/admin\/seeds\/([^/]+)$/.exec(path);
          if (request.method === 'PATCH' && seedMatch) {
            return send(200, { data: await options.admin.editSeed(seedMatch[1], input.body, input.theme, input.displayNickname, actor.id) });
          }
          if (request.method === 'POST' && path === '/api/v1/admin/moderation-rules') {
            return send(201, { data: await options.admin.addModerationRule(input.category, input.phrase, input.reason, actor.id) });
          }
          const ruleMatch = /^\/api\/v1\/admin\/moderation-rules\/([^/]+)$/.exec(path);
          if (request.method === 'PATCH' && ruleMatch) {
            return send(200, { data: await options.admin.setModerationRule(ruleMatch[1], input.active, input.reason, actor.id) });
          }
          const reviewMatch = /^\/api\/v1\/admin\/letters\/([^/]+)\/review$/.exec(path);
          if (request.method === 'POST' && reviewMatch) {
            return send(200, { data: await options.admin.reviewLetter(reviewMatch[1], input.decision, input.reason, actor.id) });
          }
          const inquiryMatch = /^\/api\/v1\/admin\/inquiries\/([^/]+)$/.exec(path);
          if (request.method === 'PATCH' && inquiryMatch) {
            return send(200, { data: await options.admin.updateInquiry(inquiryMatch[1], input.status, input.reason, actor.id) });
          }
        }
      }
      if (auth && request.method === 'POST' && path === '/api/v1/auth/recover') {
        const input = payload(body);
        const result = await auth.recover(input.nickname, input.recoveryCode, input.newPassword, clientSource);
        return send(200, { data: result });
      }
      if (auth && request.method === 'GET' && path === '/api/v1/me') {
        const result = await auth.refreshCsrf(sessionCookie(request, secure));
        return send(200, { data: result });
      }
      if (auth && request.method === 'POST' && path === '/api/v1/auth/logout') {
        await auth.logout(sessionCookie(request, secure), request.headers.get('x-csrf-token'));
        const response = send(200, { data: { loggedOut: true } });
        setSessionCookie(response, '', secure, true);
        return response;
      }
      if (auth && request.method === 'POST' && path === '/api/v1/me/recovery-code/rotate') {
        const input = payload(body);
        const result = await auth.rotateRecoveryCode(
          sessionCookie(request, secure), request.headers.get('x-csrf-token'), input.password,
        );
        return send(200, { data: result });
      }
      if (auth && request.method === 'POST' && path === '/api/v1/me/withdraw') {
        const input = payload(body);
        await auth.withdraw(sessionCookie(request, secure), request.headers.get('x-csrf-token'), input.password);
        const response = send(200, { data: { withdrawn: true } });
        setSessionCookie(response, '', secure, true);
        return response;
      }

      const riverMatch = /^\/api\/v1\/bridges\/([^/]+)\/river$/.exec(path);
      if (request.method === 'GET' && riverMatch) {
        if (!options.letters) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        return send(200, { data: await options.letters.river(riverMatch[1], options.now?.() ?? new Date()) });
      }
      const writeMatch = /^\/api\/v1\/bridges\/([^/]+)\/letters$/.exec(path);
      if (request.method === 'POST' && writeMatch) {
        if (!options.letters || !auth) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        const user = await auth.authorizeMutation(sessionCookie(request, secure), request.headers.get('x-csrf-token'));
        const input = payload(body);
        const result = await options.letters.create({
          bridgeId: writeMatch[1], questionId: input.questionId, body: input.body,
          theme: input.theme, userId: user.id, nickname: user.nickname,
          idempotencyKey: request.headers.get('idempotency-key'), now: options.now?.() ?? new Date(),
        });
        return send(result.replayed ? 200 : 201, { data: { id: result.id } });
      }
      const detailMatch = /^\/api\/v1\/letters\/([^/]+)$/.exec(path);
      if (request.method === 'GET' && detailMatch) {
        if (!options.letters) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        const letter = await options.letters.get(detailMatch[1]);
        return letter
          ? send(200, { data: letter })
          : send(404, { error: { code: 'LETTER_NOT_FOUND', message: '편지를 찾을 수 없습니다.' } });
      }

      if (request.method === 'GET' && path === '/api/v1/best') {
        if (!options.engagement) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        const query = new URL(request.url).searchParams;
        return send(200, { data: await options.engagement.best(
          query.get('scope'), query.get('bridgeId'), query.get('period'), options.now?.() ?? new Date(),
        ) });
      }
      if (request.method === 'GET' && path === '/api/v1/me/saved') {
        if (!options.engagement || !auth) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        const user = await auth.authenticate(sessionCookie(request, secure));
        return send(200, { data: await options.engagement.saved(user.id) });
      }
      const reactionMatch = /^\/api\/v1\/letters\/([^/]+)\/reaction$/.exec(path);
      const saveMatch = /^\/api\/v1\/letters\/([^/]+)\/save$/.exec(path);
      if ((request.method === 'PUT' || request.method === 'DELETE') && (reactionMatch || saveMatch)) {
        if (!options.engagement || !auth) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        const user = await auth.authorizeMutation(sessionCookie(request, secure), request.headers.get('x-csrf-token'));
        const data = reactionMatch
          ? await options.engagement.react(reactionMatch[1], user.id, request.method === 'PUT', options.now?.() ?? new Date())
          : await options.engagement.save(saveMatch![1], user.id, request.method === 'PUT');
        return send(200, { data });
      }
      const reportMatch = /^\/api\/v1\/letters\/([^/]+)\/reports$/.exec(path);
      if (request.method === 'POST' && reportMatch) {
        if (!options.reports || !auth) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        const user = await auth.authorizeMutation(sessionCookie(request, secure), request.headers.get('x-csrf-token'));
        const input = payload(body);
        return send(201, { data: await options.reports.report(reportMatch[1], user.id, input.reasonCode) });
      }
      if (request.method === 'POST' && path === '/api/v1/inquiries') {
        if (!options.inquiries) return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        if (!allowedOrigin) return send(403, { error: { code: 'ORIGIN_DENIED', message: '요청 출처를 확인해 주세요.' } });
        const input = payload(body);
        const user = input.type === 'removal'
          ? await auth?.authorizeMutation(sessionCookie(request, secure), request.headers.get('x-csrf-token'))
          : null;
        return send(201, { data: await options.inquiries.submit({
          type: input.type, body: input.body, relatedLetterId: input.relatedLetterId,
          replyEmail: input.replyEmail, requesterUserId: user?.id ?? null, source: clientSource,
        }) });
      }

      if (request.method === 'GET' && (path === '/api/v1/bridges' || path.startsWith('/api/v1/bridges/'))) {
        if (!options.bridges) {
          return send(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } });
        }
        const now = options.now?.() ?? new Date();
        if (path === '/api/v1/bridges') {
          const bridges = await options.bridges.list(now);
          return send(200, { data: bridges });
        }
        const match = /^\/api\/v1\/bridges\/([^/]+)$/.exec(path);
        const bridge = match ? await options.bridges.get(match[1], now) : null;
        return bridge
          ? send(200, { data: bridge })
          : send(404, { error: { code: 'BRIDGE_NOT_FOUND', message: '다리를 찾을 수 없습니다.' } });
      }

      return send(404, { error: { code: 'NOT_FOUND', message: '요청한 경로를 찾을 수 없습니다.' } });
    } catch (error) {
      if (error instanceof AuthError) return send(error.status, { error: { code: error.code, message: error.message } });
      if (error instanceof LetterError) return send(error.status, { error: {
        code: error.code, message: error.message, ...(error.category ? { category: error.category } : {}),
      } });
      if (error instanceof EngagementError) return send(error.status, { error: { code: error.code, message: error.message } });
      if (error instanceof ReportError) return send(error.status, { error: { code: error.code, message: error.message } });
      if (error instanceof InquiryError) return send(error.status, { error: { code: error.code, message: error.message } });
      if (error instanceof AdminError) return send(error.status, { error: { code: error.code, message: error.message } });
      if (error instanceof MetricsError) return send(error.status, { error: { code: error.code, message: error.message } });
      return send(500, { error: { code: 'INTERNAL_ERROR', message: '요청을 처리하지 못했습니다.' } });
    }
  };
}
