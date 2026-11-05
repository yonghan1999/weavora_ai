package app.weavora.zhijie.controlplane.social;

import app.weavora.zhijie.controlplane.identity.AccountStatus;

public record UserSearchView(String userId, String email, AccountStatus accountStatus) {
}
