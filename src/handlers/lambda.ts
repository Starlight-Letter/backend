import { createApp } from '../http/app';
import { Pool } from 'pg';
import { PostgresBridgeRepository } from '../db/bridges';

interface HttpApiEvent {
  rawPath: string;
  rawQueryString?: string;
  requestContext: { http: { method: string } };
  headers?: Record<string, string>;
  body?: string | null;
  isBase64Encoded?: boolean;
}

type App = ReturnType<typeof createApp>;

export function createLambdaHandler(app: App) {
  return async (event: HttpApiEvent) => {
    const query = event.rawQueryString ? `?${event.rawQueryString}` : '';
    const body = event.body == null ? undefined : event.isBase64Encoded
      ? Buffer.from(event.body, 'base64')
      : event.body;
    const method = event.requestContext.http.method;
    const request = new Request(`https://api.local${event.rawPath}${query}`, {
      method,
      headers: event.headers,
      body: method === 'GET' || method === 'HEAD' ? undefined : body,
    });
    const response = await app(request);
    return {
      statusCode: response.status,
      headers: Object.fromEntries(response.headers.entries()),
      body: await response.text(),
      isBase64Encoded: false,
    };
  };
}

const pool = process.env.DATABASE_URL
  ? new Pool({ connectionString: process.env.DATABASE_URL, max: 2 })
  : null;
export const handler = createLambdaHandler(createApp({
  bridges: pool ? new PostgresBridgeRepository(pool) : undefined,
}));
