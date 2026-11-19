import { randomUUID } from 'node:crypto';
import { IncomingMessage, ServerResponse } from 'node:http';
import { Socket } from 'node:net';

interface ConnectionState {
  id: string;
  requests: number;
}

/** Metadata describes the TCP socket observed by this backend, not the database connection. */
export function connectionMetadata() {
  const connections = new WeakMap<Socket, ConnectionState>();

  return (request: IncomingMessage, response: ServerResponse, next: () => void): void => {
    let connection = connections.get(request.socket);
    if (!connection) {
      connection = { id: randomUUID(), requests: 0 };
      connections.set(request.socket, connection);
    }
    connection.requests += 1;
    response.setHeader('X-Demo-Connection-Id', connection.id);
    response.setHeader('X-Demo-Connection-Request', String(connection.requests));

    // A cached response would carry metadata for the wrong connection.
    if (request.url?.split('?')[0] === '/api/books/search') {
      response.setHeader('Cache-Control', 'no-store');
    }
    next();
  };
}
