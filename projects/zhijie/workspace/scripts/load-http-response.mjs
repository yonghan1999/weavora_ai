const errorCodes = new Set(["RATE_LIMITED", "ACCESS_TOKEN_INVALID", "REFRESH_TOKEN_INVALID",
  "AUTH_REQUIRED", "CURRENT_GRANT_REQUIRED", "VALIDATION_FAILED"]);

export function classifyLoadResponse(status, text, operation, body) {
  let value;
  try { value = JSON.parse(text); } catch {
    return { code: status === 200 ? "RESPONSE_INVALID" : "HTTP_FAILED" };
  }
  if (status !== 200) {
    const code = value?.error?.code;
    return { code: errorCodes.has(code) ? code : "HTTP_FAILED" };
  }
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "data") || value.data == null) {
    return { code: "RESPONSE_INVALID" };
  }
  const data = value.data;
  if (operation === "heartbeat" && data.recorded !== true) return { code: "HEARTBEAT_RESPONSE_INVALID" };
  if (operation === "message-write" && (typeof data.id !== "string" || !data.id || data.body !== body.body)) {
    return { code: "WRITE_RESPONSE_INVALID" };
  }
  if (["message-read", "friends-presence"].includes(operation) && !Array.isArray(data)) return { code: "LIST_RESPONSE_INVALID" };
  return { value, code: null };
}
