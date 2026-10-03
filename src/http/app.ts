import { randomUUID } from 'node:crypto';
import type { BridgeReader } from '../db/bridges';

const MAX_BODY_BYTES = 16 * 1024;

type HealthHook = () => void | Promise<void>;

export interface AppOptions {
  onHealth?: HealthHook;
  bridges?: BridgeReader;
  now?: () => Date;
}

function json(status: number, value: unknown, requestId: string): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-request-id': requestId,
    },
  });
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
    try {
      const body = await readBody(request);
      if (body === null) {
        return json(413, { error: { code: 'PAYLOAD_TOO_LARGE', message: '요청 본문이 너무 큽니다.' } }, requestId);
      }
      if (body.length > 0 && request.headers.get('content-type')?.includes('application/json')) {
        try {
          JSON.parse(new TextDecoder().decode(body));
        } catch {
          return json(400, { error: { code: 'INVALID_JSON', message: 'JSON 형식이 올바르지 않습니다.' } }, requestId);
        }
      }

      const path = new URL(request.url).pathname;
      if (request.method === 'GET' && path === '/api/v1/health') {
        await options.onHealth?.();
        return json(200, { data: { status: 'ok' } }, requestId);
      }

      if (request.method === 'GET' && (path === '/api/v1/bridges' || path.startsWith('/api/v1/bridges/'))) {
        if (!options.bridges) {
          return json(503, { error: { code: 'SERVICE_UNAVAILABLE', message: '서비스를 이용할 수 없습니다.' } }, requestId);
        }
        const now = options.now?.() ?? new Date();
        if (path === '/api/v1/bridges') {
          const bridges = await options.bridges.list(now);
          return json(200, { data: bridges }, requestId);
        }
        const match = /^\/api\/v1\/bridges\/([^/]+)$/.exec(path);
        const bridge = match ? await options.bridges.get(match[1], now) : null;
        return bridge
          ? json(200, { data: bridge }, requestId)
          : json(404, { error: { code: 'BRIDGE_NOT_FOUND', message: '다리를 찾을 수 없습니다.' } }, requestId);
      }

      return json(404, { error: { code: 'NOT_FOUND', message: '요청한 경로를 찾을 수 없습니다.' } }, requestId);
    } catch {
      return json(500, { error: { code: 'INTERNAL_ERROR', message: '요청을 처리하지 못했습니다.' } }, requestId);
    }
  };
}
