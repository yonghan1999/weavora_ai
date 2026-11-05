package app.weavora.zhijie.controlplane.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService identityService;

    public AuthController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        identityService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("data", Map.of("verificationRequired", true)));
    }

    @PostMapping("/verify-email")
    public Map<String, Object> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        identityService.verifyEmail(request.email(), request.code());
        return Map.of("data", Map.of("verified", true));
    }

    @PostMapping("/login")
    public Map<String, AuthTokens> login(@Valid @RequestBody LoginRequest request) {
        return Map.of("data", identityService.login(request.email(), request.password(), request.deviceId()));
    }

    @PostMapping("/refresh")
    public Map<String, AuthTokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return Map.of("data", identityService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@Valid @RequestBody LogoutRequest request) {
        identityService.logout(request.accessToken());
        return Map.of("data", Map.of("loggedOut", true));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<Map<String, Object>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        identityService.forgotPassword(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("data", Map.of("accepted", true)));
    }

    @PostMapping("/password/reset")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        identityService.resetPassword(request.email(), request.code(), request.newPassword());
        return Map.of("data", Map.of("reset", true));
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 12, max = 128) String password
    ) {
    }

    public record VerifyEmailRequest(@NotBlank @Email String email, @NotBlank String code) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank String deviceId
    ) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(@NotBlank String accessToken) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank String code,
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {
    }
}
