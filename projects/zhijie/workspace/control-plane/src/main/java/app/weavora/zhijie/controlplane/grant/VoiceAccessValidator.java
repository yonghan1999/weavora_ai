package app.weavora.zhijie.controlplane.grant;

import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import app.weavora.zhijie.controlplane.room.ActivityState;
import app.weavora.zhijie.controlplane.room.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** SFU 信令入口每次查权威资格，覆盖 SDK 刷新令牌和旧令牌重入。 */
@Service
public class VoiceAccessValidator {
    private final String apiKey;
    private final byte[] apiSecret;
    private final ActivityRepository activities;
    private final RoomRepository rooms;
    private final IdentityRepository identities;
    private final ObjectMapper json;
    private final Clock clock;
    private final app.weavora.zhijie.controlplane.group.GroupService groups;

    public VoiceAccessValidator(@Value("${zhijie.voice.api-key:}") String apiKey,
                                @Value("${zhijie.voice.api-secret:}") String apiSecret,
                                ActivityRepository activities, RoomRepository rooms,
                                IdentityRepository identities, ObjectMapper json, Clock clock,
                                app.weavora.zhijie.controlplane.group.GroupService groups) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret.getBytes(StandardCharsets.UTF_8);
        this.activities = activities;
        this.rooms = rooms;
        this.identities = identities;
        this.json = json;
        this.clock = clock;
        this.groups = groups;
    }

    public void validate(String token) {
        try {
            if (apiKey.isBlank() || apiSecret.length < 32 || token == null || token.length() > 8192) throw denied();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw denied();
            var decoder = Base64.getUrlDecoder();
            var header = json.readTree(decoder.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) throw denied();
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret, "HmacSHA256"));
            byte[] signature = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(signature, decoder.decode(parts[2]))) throw denied();
            var claims = json.readTree(decoder.decode(parts[1]));
            long now = clock.instant().getEpochSecond();
            if (!apiKey.equals(claims.path("iss").asText()) || !claims.path("exp").isIntegralNumber()
                    || claims.path("exp").asLong() <= now || claims.path("nbf").asLong(0) > now) throw denied();
            var video = claims.path("video");
            if (!video.path("roomJoin").asBoolean() || video.path("roomAdmin").asBoolean()
                    || video.path("roomCreate").asBoolean()) throw denied();
            String activityId = video.path("room").asText();
            String subject = claims.path("sub").asText();
            int separator = subject.lastIndexOf(':');
            if (separator <= 0 || activityId.isBlank()) throw denied();
            String userId = subject.substring(0, separator);
            long generation = Long.parseLong(subject.substring(separator + 1));
            identities.findUserById(userId).filter(user -> user.status() == AccountStatus.ACTIVE).orElseThrow(this::denied);
            var activity = rooms.findActivity(activityId).filter(item -> item.state() == ActivityState.ACTIVE).orElseThrow(this::denied);
            var room = rooms.findRoom(activity.roomId()).filter(item -> item.lifecycle() == app.weavora.zhijie.controlplane.room.RoomLifecycle.AVAILABLE).orElseThrow(this::denied);
            try { groups.requireMembership(room.groupId(), userId); }
            catch (ApiException exception) { throw denied(); }
            var member = activities.findMember(activityId, userId)
                    .filter(item -> item.memberGeneration() == generation)
                    .filter(item -> item.state() == MemberState.ACTIVE || item.state() == MemberState.GRACE)
                    .orElseThrow(this::denied);
            activities.findLatestLease(activityId, userId)
                    .filter(lease -> lease.revokedAt() == null && clock.instant().isBefore(lease.expiresAt()))
                    .filter(lease -> lease.memberGeneration() == generation
                            && lease.activityGeneration().equals(activity.activityGeneration())
                            && lease.deviceId().equals(member.deviceId()))
                    .orElseThrow(this::denied);
            var pointer = activities.findAccountActivity(userId)
                    .filter(item -> item.activityId().equals(activityId)
                            && item.memberGeneration() == generation && item.deviceId().equals(member.deviceId()))
                    .orElseThrow(this::denied);
            if (pointer.authSessionId() == null) throw denied();
            identities.findSessionById(pointer.authSessionId())
                    .filter(session -> session.allowsActivity(userId, member.deviceId(), clock.instant())).orElseThrow(this::denied);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            // 签名、令牌原文、第三方错误不进入客户端响应或异常链。
            throw denied();
        }
    }

    private ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "VOICE_ACCESS_DENIED", "当前语音资格无效或已过期", false);
    }
}
