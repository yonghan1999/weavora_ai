package app.weavora.zhijie.controlplane.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.identity.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SocialServiceTest {
    private InMemorySocialRepository repository;
    private SocialService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySocialRepository();
        IdentityService identityService = mock(IdentityService.class);
        when(identityService.requireActive(anyString())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0);
            UserSearchView user = repository.users.get(userId);
            if (user == null || user.accountStatus() != AccountStatus.ACTIVE) {
                throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "账号无效", false);
            }
            return new UserAccount(userId, user.email(), user.accountStatus(), "hidden", Instant.now());
        });
        service = new SocialService(repository, identityService, Clock.systemUTC());
        repository.addUser("user-a", "a@example.com", AccountStatus.ACTIVE);
        repository.addUser("user-b", "b@example.com", AccountStatus.ACTIVE);
        repository.addUser("user-c", "c@example.com", AccountStatus.ACTIVE);
    }

    @Test
    void requestAcceptSearchAndPresenceFormRealFriendList() {
        FriendRequest request = service.sendRequest("user-a", "user-b");
        service.accept("user-b", request.id());
        service.heartbeat("user-b");

        assertThat(service.listFriends("user-a")).singleElement().satisfies(friend -> {
            assertThat(friend.userId()).isEqualTo("user-b");
            assertThat(friend.presence()).isEqualTo("ONLINE");
        });
        assertThat(service.search("user-a", "@example.com")).extracting(UserSearchView::userId)
                .containsExactly("user-b", "user-c");
        repository.presence.put("user-b", Instant.now().minusSeconds(120));
        assertThat(service.listFriends("user-a")).singleElement().extracting(FriendView::presence).isEqualTo("OFFLINE");
    }

    @Test
    void onlyAddresseeCanRejectAndClosedRequestCannotBeReused() {
        FriendRequest request = service.sendRequest("user-a", "user-b");

        assertThatThrownBy(() -> service.reject("user-a", request.id()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CURRENT_GRANT_REQUIRED");
        assertThat(service.reject("user-b", request.id()).status()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThatThrownBy(() -> service.accept("user-b", request.id()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FRIEND_REQUEST_CLOSED");
        assertThat(repository.friendships).isEmpty();
    }

    @Test
    void duplicateRequestsAreRejectedAndFriendshipCanBeDeletedByEitherSide() {
        FriendRequest request = service.sendRequest("user-a", "user-b");
        assertThatThrownBy(() -> service.sendRequest("user-b", "user-a"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FRIEND_REQUEST_PENDING");
        service.accept("user-b", request.id());
        assertThatThrownBy(() -> service.sendRequest("user-a", "user-b"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FRIENDSHIP_EXISTS");

        service.deleteFriend("user-b", "user-a");
        assertThat(service.listFriends("user-a")).isEmpty();
        assertThatThrownBy(() -> service.deleteFriend("user-a", "user-b"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FRIENDSHIP_NOT_FOUND");
    }

    @Test
    void inactiveUsersCannotCreateOrAcceptRequests() {
        repository.addUser("user-disabled", "disabled@example.com", AccountStatus.DISABLED);
        assertThatThrownBy(() -> service.sendRequest("user-a", "user-disabled"))
                .isInstanceOf(ApiException.class);

        FriendRequest request = service.sendRequest("user-a", "user-b");
        repository.addUser("user-a", "a@example.com", AccountStatus.DISABLED);
        assertThatThrownBy(() -> service.accept("user-b", request.id())).isInstanceOf(ApiException.class);
    }

    private static class InMemorySocialRepository implements SocialRepository {
        private final Map<String, UserSearchView> users = new HashMap<>();
        private final Map<String, FriendRequest> requests = new HashMap<>();
        private final Map<String, Friendship> friendships = new HashMap<>();
        private final Map<String, Instant> presence = new HashMap<>();

        void addUser(String userId, String email, AccountStatus status) {
            users.put(userId, new UserSearchView(userId, email, status));
        }

        public List<UserSearchView> searchActiveUsers(String query, String excludedUserId, int limit) {
            return users.values().stream()
                    .filter(user -> user.accountStatus() == AccountStatus.ACTIVE && !user.userId().equals(excludedUserId))
                    .filter(user -> user.email().contains(query)).sorted((a, b) -> a.email().compareTo(b.email()))
                    .limit(limit).toList();
        }

        public Optional<FriendRequest> findRequestById(String requestId) {
            return Optional.ofNullable(requests.get(requestId));
        }

        public Optional<FriendRequest> findPendingRequestBetween(String firstUserId, String secondUserId) {
            return requests.values().stream().filter(request -> request.status() == FriendRequestStatus.PENDING)
                    .filter(request -> SetLike.pair(request.requesterId(), request.addresseeId())
                            .equals(SetLike.pair(firstUserId, secondUserId))).findFirst();
        }

        public void insertRequest(FriendRequest request) {
            requests.put(request.id(), request);
        }

        public void updateRequest(FriendRequest request) {
            requests.put(request.id(), request);
        }

        public Optional<Friendship> findFriendship(String firstUserId, String secondUserId) {
            return friendships.values().stream().filter(item -> item.userLowId().equals(Friendship.low(firstUserId, secondUserId))
                    && item.userHighId().equals(Friendship.high(firstUserId, secondUserId))).findFirst();
        }

        public List<Friendship> listFriendships(String userId) {
            return friendships.values().stream()
                    .filter(item -> item.userLowId().equals(userId) || item.userHighId().equals(userId)).toList();
        }

        public void insertFriendship(Friendship friendship) {
            friendships.put(friendship.id(), friendship);
        }

        public void deleteFriendship(String firstUserId, String secondUserId) {
            friendships.values().removeIf(item -> item.userLowId().equals(firstUserId) && item.userHighId().equals(secondUserId));
        }

        public List<UserSearchView> findUsersByIds(List<String> userIds) {
            return userIds.stream().map(users::get).filter(java.util.Objects::nonNull).toList();
        }

        public void recordHeartbeat(String userId, Instant seenAt) {
            presence.put(userId, seenAt);
        }

        public Optional<Instant> findLastSeen(String userId) {
            return Optional.ofNullable(presence.get(userId));
        }
    }

    private record SetLike(String first, String second) {
        static SetLike pair(String first, String second) {
            return new SetLike(Friendship.low(first, second), Friendship.high(first, second));
        }
    }
}
