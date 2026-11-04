package app.weavora.zhijie.controlplane.room;

import java.time.Instant;

public record Activity(
        String id, String roomId, Long activityGeneration, ActivityState state, Instant startedAt, Instant endedAt
) {
}
