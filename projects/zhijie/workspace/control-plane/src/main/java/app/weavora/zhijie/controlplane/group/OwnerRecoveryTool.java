package app.weavora.zhijie.controlplane.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

/** 离线人工支持工具；不作为 Spring Bean 或 HTTP 接口注册。 */
public final class OwnerRecoveryTool {
    public record Review(String requestId, String expectedOwnerUserId, String targetUserId,
                         String reviewerId, String reasonRef, String decision,
                         boolean evidenceVerified, boolean ownerUnavailable, boolean noDispute) {}

    private OwnerRecoveryTool() {}

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2
                    || (args.length == 2 && !args[1].equals("--execute-human-review"))) {
                throw new IllegalArgumentException("USAGE: review.json [--execute-human-review]");
            }
            Review review = new ObjectMapper().readValue(Files.readString(Path.of(args[0])), Review.class);
            boolean execute = args.length == 2;
            try (var connection = DriverManager.getConnection(requiredEnv("ZHIJIE_SUPPORT_JDBC_URL"),
                    requiredEnv("ZHIJIE_SUPPORT_DB_USERNAME"), requiredEnv("ZHIJIE_SUPPORT_DB_PASSWORD"))) {
                review(connection, review, execute);
            }
            System.out.println(execute ? "OWNER_RECOVERY_REVIEW_COMMITTED" : "OWNER_RECOVERY_REVIEW_VALIDATED_ROLLED_BACK");
        } catch (Exception failure) {
            // JDBC 原文可能包含连接信息；只输出稳定错误码。
            System.err.println(failure instanceof IllegalArgumentException
                    ? failure.getMessage() : "OWNER_RECOVERY_REVIEW_FAILED");
            System.exit(1);
        }
    }

    public static void review(Connection connection, Review review, boolean execute) throws Exception {
        validate(review);
        if (!connection.getAutoCommit()) throw new IllegalArgumentException("DEDICATED_CONNECTION_REQUIRED");
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'");
                statement.execute("SET LOCAL statement_timeout = '10s'");
            }
            String groupId = scalar(connection, "SELECT group_id FROM owner_recovery_requests WHERE id=?", review.requestId());
            if (groupId == null) fail("RECOVERY_NOT_AVAILABLE");
            String owner = null;
            try (var statement = connection.prepareStatement("SELECT owner_user_id, status FROM groups WHERE id=? FOR UPDATE")) {
                statement.setString(1, groupId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getString("status").equals("ACTIVE")) fail("GROUP_NOT_AVAILABLE");
                    owner = rows.getString("owner_user_id");
                }
            }
            String requester;
            String expected;
            try (var statement = connection.prepareStatement("SELECT requester_id, status, expected_owner_user_id FROM owner_recovery_requests WHERE id=? FOR UPDATE")) {
                statement.setString(1, review.requestId());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getString("status").equals("PENDING")) fail("RECOVERY_ALREADY_DECIDED");
                    requester = rows.getString("requester_id");
                    expected = rows.getString("expected_owner_user_id");
                }
            }
            if (review.reviewerId().equals(requester) || review.reviewerId().equals(owner)
                    || review.reviewerId().equals(review.targetUserId())) fail("INDEPENDENT_REVIEWER_REQUIRED");
            boolean recover = review.decision().equals("RECOVER");
            if (recover) {
                if (!review.evidenceVerified() || !review.ownerUnavailable() || !review.noDispute()) fail("HUMAN_EVIDENCE_CONFIRMATIONS_REQUIRED");
                if (expected == null || !expected.equals(owner) || !owner.equals(review.expectedOwnerUserId())) fail("OWNER_CHANGED_SINCE_REQUEST");
                String target = review.targetUserId();
                if (target == null || target.equals(owner)) fail("RECOVERY_TARGET_INVALID");
                // 与业务一致先群后账号；排序避免支持工具间反向锁。
                for (String user : java.util.stream.Stream.of(owner, target).sorted().toList()) {
                    String status = scalar(connection, "SELECT status FROM users WHERE id=? FOR UPDATE", user);
                    if (status == null || (user.equals(target) && !status.equals("ACTIVE"))) fail("RECOVERY_TARGET_INACTIVE");
                }
                if (!"OWNER".equals(scalar(connection, "SELECT role FROM group_members WHERE group_id=? AND user_id=? AND status='ACTIVE'", groupId, owner))
                        || scalar(connection, "SELECT role FROM group_members WHERE group_id=? AND user_id=? AND status='ACTIVE'", groupId, target) == null) {
                    fail("CURRENT_MEMBERSHIP_REQUIRED");
                }
                update(connection, "UPDATE group_members SET role='MEMBER' WHERE group_id=? AND user_id=?", groupId, owner);
                update(connection, "UPDATE group_members SET role='OWNER' WHERE group_id=? AND user_id=?", groupId, target);
                update(connection, "UPDATE groups SET owner_user_id=? WHERE id=?", target, groupId);
                try (var statement = connection.prepareStatement("UPDATE owner_transfers SET status='CANCELLED', completed_at=CURRENT_TIMESTAMP WHERE group_id=? AND status='PENDING'")) {
                    statement.setString(1, groupId);
                    statement.executeUpdate();
                }
            }
            update(connection, "UPDATE owner_recovery_requests SET status=?, reviewer_id=?, decision_reason=?, target_user_id=?, decided_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
                    recover ? "RESOLVED" : "REJECTED", review.reviewerId(), review.reasonRef(), recover ? review.targetUserId() : null, review.requestId());
            update(connection, "INSERT INTO audit_logs(id, group_id, actor_id, target_type, target_id, action, result, occurred_at, correlation_id) VALUES (?, ?, ?, 'OWNER_RECOVERY', ?, 'OWNER_RECOVERY_REVIEW', ?, CURRENT_TIMESTAMP, ?)",
                    UUID.randomUUID().toString(), groupId, review.reviewerId(), review.requestId(), review.decision(), UUID.randomUUID().toString());
            if (execute) connection.commit();
            else connection.rollback();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void validate(Review review) {
        if (review == null) fail("REVIEW_REQUIRED");
        for (String value : new String[]{review.requestId(), review.reviewerId(), review.reasonRef()}) {
            if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,127}")) fail("REVIEW_REFERENCE_INVALID");
        }
        if (!java.util.Set.of("RECOVER", "REJECT", "DISPUTED", "INSUFFICIENT_EVIDENCE").contains(review.decision() == null ? "" : review.decision())) fail("REVIEW_DECISION_INVALID");
    }

    private static String scalar(Connection connection, String sql, String... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private static void update(Connection connection, String sql, String... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            if (statement.executeUpdate() != 1) fail("RECOVERY_CONCURRENT_CHANGE");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SUPPORT_DATABASE_CONFIGURATION_REQUIRED");
        return value;
    }

    private static void fail(String code) { throw new IllegalArgumentException(code); }
}
