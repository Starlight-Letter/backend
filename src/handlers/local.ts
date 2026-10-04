import { createServer, type Server } from 'node:http';
import { pathToFileURL } from 'node:url';
import { Pool } from 'pg';
import { PostgresBridgeRepository } from '../db/bridges';
import { PostgresAuthService } from '../domains/auth';
import { PostgresLetterService } from '../domains/letters';
import { PostgresEngagementService } from '../domains/engagement';
import { PostgresReportService } from '../domains/reports';
import { PostgresInquiryService } from '../domains/inquiries';
import { PostgresAdminService } from '../domains/admin';
import { PostgresMetricsService } from '../domains/metrics';
import { PostgresSurveyService } from '../domains/survey';
import { createApp } from '../http/app';

type App = ReturnType<typeof createApp>;

export async function startServer(app: App, port: number): Promise<Server> {
  const server = createServer(async (incoming, outgoing) => {
    try {
      const clientIp = incoming.socket.remoteAddress ?? 'unknown';
      const chunks: Buffer[] = [];
      let size = 0;
      for await (const chunk of incoming) {
        const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        chunks.push(bytes);
        size += bytes.length;
        if (size > 16 * 1024) break;
      }

      const method = incoming.method ?? 'GET';
      const headers = new Headers();
      for (const [name, value] of Object.entries(incoming.headers)) {
        if (typeof value === 'string') headers.set(name, value);
        else if (Array.isArray(value)) value.forEach((item) => headers.append(name, item));
      }
      headers.set('x-starlight-client-ip', clientIp);
      const request = new Request(`http://localhost${incoming.url ?? '/'}`, {
        method,
        headers,
        body: method === 'GET' || method === 'HEAD' ? undefined : Buffer.concat(chunks),
      });
      const response = await app(request);
      outgoing.writeHead(response.status, Object.fromEntries(response.headers.entries()));
      outgoing.end(Buffer.from(await response.arrayBuffer()));
    } catch {
      outgoing.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
      outgoing.end(JSON.stringify({ error: { code: 'INTERNAL_ERROR', message: '요청을 처리하지 못했습니다.' } }));
    }
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required');
  const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 5 });
  const port = Number(process.env.PORT ?? 3000);
  startServer(createApp({
    bridges: new PostgresBridgeRepository(pool),
    auth: new PostgresAuthService(pool, process.env.ADMIN_TOTP_KEY ? Buffer.from(process.env.ADMIN_TOTP_KEY, 'base64') : undefined),
    letters: new PostgresLetterService(pool),
    engagement: new PostgresEngagementService(pool),
    reports: new PostgresReportService(pool),
    inquiries: new PostgresInquiryService(pool),
    admin: new PostgresAdminService(pool),
    metrics: new PostgresMetricsService(pool),
    survey: new PostgresSurveyService(pool),
    allowedOrigins: [process.env.APP_ORIGIN ?? 'http://localhost:5173'],
    secureCookies: false,
  }), port).then(() => {
    process.stdout.write(`API listening on http://127.0.0.1:${port}\n`);
  });
}
