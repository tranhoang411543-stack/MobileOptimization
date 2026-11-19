import { readFileSync } from 'node:fs';
import { createServer, Server } from 'node:https';
import { IncomingMessage, ServerResponse } from 'node:http';
import { join } from 'node:path';

type Handler = (request: IncomingMessage, response: ServerResponse) => void;
export interface DemoServers { trusted: Server; untrusted: Server; close(): Promise<void>; }

/** The two local TLS listeners expose only App 7's fixed demo route. */
export async function startDemoHttps(
  handler: Handler, directory: string, trustedPort = 3443, untrustedPort = 3444,
): Promise<DemoServers> {
  const serveDemoOnly: Handler = (request, response) => {
    if (request.method !== 'POST' || request.url !== '/api/transport-demo/submit') {
      response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
      response.end('Not found');
      return;
    }
    handler(request, response);
  };
  const trusted = createServer({ key: readFileSync(join(directory, 'server.key')),
    cert: readFileSync(join(directory, 'server.pem')), minVersion: 'TLSv1.2' }, serveDemoOnly);
  const untrusted = createServer({ key: readFileSync(join(directory, 'untrusted.key')),
    cert: readFileSync(join(directory, 'untrusted.pem')), minVersion: 'TLSv1.2' }, serveDemoOnly);

  const listen = (server: Server, port: number): Promise<void> => new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => { server.removeListener('error', reject); resolve(); });
  });
  const close = (server: Server): Promise<void> => new Promise((resolve) => {
    if (!server.listening) return resolve();
    server.close(() => resolve());
  });
  try {
    await listen(trusted, trustedPort);
    await listen(untrusted, untrustedPort);
  } catch (error) {
    await close(trusted);
    await close(untrusted);
    throw error;
  }
  return { trusted, untrusted, close: async () => { await Promise.all([close(trusted), close(untrusted)]); } };
}
