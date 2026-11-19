package app.weavora.zhijie.controlplane.social;

import app.weavora.zhijie.controlplane.identity.AccountStatus;
import java.time.Instant;

public record IncomingFriendRequest(String id, String requesterId, String email,
                                    AccountStatus accountStatus, Instant createdAt) {
}
