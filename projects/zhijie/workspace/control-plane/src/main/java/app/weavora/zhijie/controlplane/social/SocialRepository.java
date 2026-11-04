package app.weavora.zhijie.controlplane.social;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SocialRepository {
    List<UserSearchView> searchActiveUsers(String query, String excludedUserId, int limit);

    Optional<FriendRequest> findRequestById(String requestId);

    Optional<FriendRequest> findPendingRequestBetween(String firstUserId, String secondUserId);

    void insertRequest(FriendRequest request);

    void updateRequest(FriendRequest request);

    Optional<Friendship> findFriendship(String firstUserId, String secondUserId);

    List<Friendship> listFriendships(String userId);

    void insertFriendship(Friendship friendship);

    void deleteFriendship(String firstUserId, String secondUserId);

    List<UserSearchView> findUsersByIds(List<String> userIds);

    void recordHeartbeat(String userId, Instant seenAt);

    Optional<Instant> findLastSeen(String userId);
}
