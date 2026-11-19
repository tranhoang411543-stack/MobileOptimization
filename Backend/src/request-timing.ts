import { Logger } from '@nestjs/common';
import { IncomingMessage, ServerResponse } from 'node:http';
import { performance } from 'node:perf_hooks';

const logger = new Logger('RequestTiming');

/** Measures the backend portion of each request until the response finishes or closes. */
export function requestTiming(write: (message: string) => void = (message) => logger.log(message)) {
  return (request: IncomingMessage, response: ServerResponse, next: () => void): void => {
    const startedAt = performance.now();
    const startedTime = new Date();
    let logged = false;

    const logOnce = (finished: boolean): void => {
      if (logged) return;
      logged = true;

      const elapsedMs = (performance.now() - startedAt).toFixed(2);
      const time = [startedTime.getHours(), startedTime.getMinutes(), startedTime.getSeconds()]
        .map((value) => String(value).padStart(2, '0')).join(':') +
        `.${String(startedTime.getMilliseconds()).padStart(3, '0')}`;
      const path = (request.url?.split('?', 1)[0] || '/').slice(0, 256);
      const status = response.headersSent ? `HTTP ${response.statusCode}` : 'chưa gửi HTTP';
      const outcome = finished ? status : `Gián đoạn (${status})`;
      const connectionId = response.getHeader('X-Demo-Connection-Id');
      const connectionNumber = response.getHeader('X-Demo-Connection-Request');
      const id = connectionId ? String(connectionId).slice(0, 8) : '—';
      const number = connectionNumber ? String(connectionNumber) : '—';

      // Exclude query strings and bodies; they can contain private data in future demos.
      write(`Nhận lúc ${time} | ${request.method || '?'} ${JSON.stringify(path)} | ${outcome}` +
        ` | Backend: ${elapsedMs} ms | Kết nối: ${id} · lần ${number}`);
    };

    response.once('finish', () => logOnce(true));
    response.once('close', () => logOnce(false));
    next();
  };
}
