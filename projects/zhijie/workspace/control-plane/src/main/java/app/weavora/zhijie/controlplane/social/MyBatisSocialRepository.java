package app.weavora.zhijie.controlplane.social;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisSocialRepository implements SocialRepository {
    private final SocialMapper mapper;

    public MyBatisSocialRepository(SocialMapper mapper) {
        this.mapper = mapper;
    }

    public List<UserSearchView> searchActiveUsers(String query, String excludedUserId, int limit) {
        return mapper.searchActiveUsers(query, excludedUserId, limit);
    }

    public Optional<FriendRequest> findRequestById(String requestId) {
        return mapper.findRequestById(requestId);
    }

    public Optional<FriendRequest> findPendingRequestBetween(String firstUserId, String secondUserId) {
        return mapper.findPendingRequestBetween(firstUserId, secondUserId);
    }

    public void insertRequest(FriendRequest request) {
        mapper.insertRequest(request);
    }

    public void updateRequest(FriendRequest request) {
        mapper.updateRequest(request);
    }

    public Optional<Friendship> findFriendship(String firstUserId, String secondUserId) {
        return mapper.findFriendship(Friendship.low(firstUserId, secondUserId), Friendship.high(firstUserId, secondUserId));
    }

    public List<Friendship> listFriendships(String userId) {
        return mapper.listFriendships(userId);
    }

    public void insertFriendship(Friendship friendship) {
        mapper.insertFriendship(friendship);
    }

    public void deleteFriendship(String firstUserId, String secondUserId) {
        mapper.deleteFriendship(Friendship.low(firstUserId, secondUserId), Friendship.high(firstUserId, secondUserId));
    }

    public List<UserSearchView> findUsersByIds(List<String> userIds) {
        return userIds.isEmpty() ? List.of() : mapper.findUsersByIds(userIds);
    }

    public void recordHeartbeat(String userId, Instant seenAt) {
        mapper.recordHeartbeat(userId, seenAt);
    }

    public Optional<Instant> findLastSeen(String userId) {
        return mapper.findLastSeen(userId);
    }
}
