package app.weavora.zhijie.controlplane.social;

import app.weavora.zhijie.controlplane.identity.AccountStatus;

public record FriendView(String userId, String email, AccountStatus accountStatus, String presence) {
}
