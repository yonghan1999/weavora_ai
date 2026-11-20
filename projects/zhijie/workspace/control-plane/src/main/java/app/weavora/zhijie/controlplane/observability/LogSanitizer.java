package app.weavora.zhijie.controlplane.observability;

import java.util.regex.Pattern;

public final class LogSanitizer {
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(?<![\\w-])([\"']?)([a-z0-9_-]*(?:password|passwd|passcode|token|ticket|secret)|" +
                    "private[_-]?key|api[_-]?key|signaling[_-]?key|management[_-]?key|(?:x[_-]?)?relay[_-]?key|" +
                    "authorization|(?:verification|reset)[_-]?code|code)(\\1\\s*[=:]\\s*)" +
                    "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^,;\\s]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]+");
    private static final Pattern ARGON2 = Pattern.compile("\\$argon2(?:id|i|d)\\$[^\\s]+");

    private LogSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null) return null;
        String safe = BEARER.matcher(message).replaceAll("Bearer [REDACTED]");
        safe = SENSITIVE_FIELD.matcher(safe).replaceAll(match -> match.group(1) + match.group(2) + match.group(3) + "[REDACTED]");
        return ARGON2.matcher(safe).replaceAll("[REDACTED_PASSWORD_HASH]");
    }
}
