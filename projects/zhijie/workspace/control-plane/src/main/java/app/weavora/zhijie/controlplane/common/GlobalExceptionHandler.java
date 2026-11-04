package app.weavora.zhijie.controlplane.common;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
        return error(exception.status(), exception.code(), exception.getMessage(), exception.retryable());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "请求字段未通过校验", false);
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message, boolean retryable
    ) {
        String correlationId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "code", code,
                "message", message,
                "retryable", retryable,
                "correlation_id", correlationId
        )));
    }
}
