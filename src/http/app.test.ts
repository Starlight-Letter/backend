import { describe, expect, it } from 'vitest';
import { createApp } from './app';

describe('HTTP contract', () => {
  const app = createApp();

  it('returns the versioned success envelope and a request ID', async () => {
    const response = await app(new Request('http://localhost/api/v1/health'));
    expect(response.status).toBe(200);
    expect(response.headers.get('x-request-id')).toBeTruthy();
    expect(await response.json()).toEqual({ data: { status: 'ok' } });
  });

  it('returns a stable 404 envelope for an unknown route', async () => {
    const response = await app(new Request('http://localhost/api/v1/missing'));
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: { code: 'NOT_FOUND', message: '요청한 경로를 찾을 수 없습니다.' } });
  });

  it('rejects a request body larger than 16 KiB', async () => {
    const response = await app(new Request('http://localhost/api/v1/health', {
      method: 'POST',
      body: 'x'.repeat(16 * 1024 + 1),
    }));
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: { code: 'PAYLOAD_TOO_LARGE', message: '요청 본문이 너무 큽니다.' } });
  });

  it('rejects malformed JSON before routing', async () => {
    const response = await app(new Request('http://localhost/api/v1/missing', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{not-json',
    }));
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: { code: 'INVALID_JSON', message: 'JSON 형식이 올바르지 않습니다.' } });
  });

  it('does not expose a thrown error to a client', async () => {
    const broken = createApp({ onHealth: () => { throw new Error('secret connection details'); } });
    const response = await broken(new Request('http://localhost/api/v1/health'));
    expect(response.status).toBe(500);
    expect(JSON.stringify(await response.json())).not.toContain('secret connection details');
  });
});
