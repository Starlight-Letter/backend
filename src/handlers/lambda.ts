import { createApp } from '../http/app';
import { Pool } from 'pg';
import { PostgresBridgeRepository } from '../db/bridges';
import { PostgresAuthService } from '../domains/auth';
import { PostgresLetterService } from '../domains/letters';
import { PostgresEngagementService } from '../domains/engagement';
import { PostgresReportService } from '../domains/reports';
import { PostgresInquiryService } from '../domains/inquiries';

interface HttpApiEvent {
  rawPath: string;
  rawQueryString?: string;
  requestContext: { http: { method: string; sourceIp?: string } };
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
    const headers = new Headers(event.headers);
    headers.set('x-starlight-client-ip', event.requestContext.http.sourceIp ?? 'unknown');
    const request = new Request(`https://api.local${event.rawPath}${query}`, {
      method,
      headers,
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
  auth: pool ? new PostgresAuthService(pool) : undefined,
  letters: pool ? new PostgresLetterService(pool) : undefined,
  engagement: pool ? new PostgresEngagementService(pool) : undefined,
  reports: pool ? new PostgresReportService(pool) : undefined,
  inquiries: pool ? new PostgresInquiryService(pool) : undefined,
  allowedOrigins: process.env.APP_ORIGIN ? [process.env.APP_ORIGIN] : [],
  secureCookies: true,
}));
