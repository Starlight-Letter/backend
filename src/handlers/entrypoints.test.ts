import { afterEach, describe, expect, it } from 'vitest';
import type { Server } from 'node:http';
import { createApp } from '../http/app';
import { createLambdaHandler } from './lambda';
import { startServer } from './local';

describe('HTTP entrypoints', () => {
  let server: Server | undefined;
  afterEach(async () => {
    if (server) await new Promise<void>((resolve) => server!.close(() => resolve()));
    server = undefined;
  });

  it('serves the same API through a local HTTP port', async () => {
    server = await startServer(createApp(), 0);
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Expected TCP address');
    const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/health`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ data: { status: 'ok' } });
  });

  it('returns 413 over the local socket when a request body is too large', async () => {
    server = await startServer(createApp(), 0);
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Expected TCP address');
    const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/health`, {
      method: 'POST',
      body: 'x'.repeat(16 * 1024 + 1),
    });
    expect(response.status).toBe(413);
  });

  it('maps an API Gateway HTTP API event to the same response', async () => {
    const handler = createLambdaHandler(createApp());
    const result = await handler({
      rawPath: '/api/v1/health',
      rawQueryString: '',
      requestContext: { http: { method: 'GET' } },
      headers: { host: 'api.example.test' },
      body: null,
      isBase64Encoded: false,
    });
    expect(result.statusCode).toBe(200);
    expect(JSON.parse(result.body)).toEqual({ data: { status: 'ok' } });
    expect(result.headers['x-request-id']).toBeTruthy();
  });
});
