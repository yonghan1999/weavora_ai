package app.weavora.zhijie.controlplane.social;

import java.time.Instant;

public record Friendship(String id, String userLowId, String userHighId, Instant createdAt) {
    public static String low(String first, String second) {
        return first.compareTo(second) < 0 ? first : second;
    }

    public static String high(String first, String second) {
        return first.compareTo(second) < 0 ? second : first;
    }
}
