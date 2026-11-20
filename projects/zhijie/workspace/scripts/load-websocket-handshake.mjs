import { AsyncLocalStorage } from "node:async_hooks";
import { channel } from "node:diagnostics_channel";

const connecting = new AsyncLocalStorage();
const attempts = new WeakMap();
const transportCodes = new Set(["UND_ERR_SOCKET", "UND_ERR_CONNECT_TIMEOUT", "ECONNRESET", "ECONNREFUSED", "ETIMEDOUT", "EPIPE"]);

channel("undici:request:create").subscribe(({ request }) => {
  const attempt = connecting.getStore();
  if (attempt && typeof request.upgrade === "string" && request.upgrade.toLowerCase() === "websocket") attempts.set(request, attempt);
});
channel("undici:request:headers").subscribe(({ request, response }) => {
  const attempt = attempts.get(request);
  if (attempt && Number.isInteger(response.statusCode) && response.statusCode >= 100 && response.statusCode <= 599) {
    attempt.httpStatus = response.statusCode;
  }
});
channel("undici:client:sendHeaders").subscribe(({ request, socket }) => {
  const attempt = attempts.get(request);
  if (attempt && Number.isInteger(socket?.localPort) && socket.localPort > 0 && socket.localPort <= 65535) {
    attempt.clientPort = socket.localPort;
  }
});
channel("undici:request:error").subscribe(({ request, error }) => {
  const attempt = attempts.get(request);
  if (attempt) attempt.transportCode = transportCodes.has(error?.code) ? error.code
    : error?.name === "AbortError" && error?.code === 20 ? "ABORT_ERROR" : "OTHER";
});

export function openObservedSocket(address) {
  const handshake = { httpStatus: null, transportCode: null, clientPort: null, startedAt: new Date().toISOString() };
  const socket = connecting.run(handshake, () => new WebSocket(address));
  return { socket, handshake };
}
