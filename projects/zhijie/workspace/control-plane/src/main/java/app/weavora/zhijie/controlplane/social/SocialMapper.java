package app.weavora.zhijie.controlplane.social;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SocialMapper {
    List<UserSearchView> searchActiveUsers(@Param("query") String query, @Param("excludedUserId") String excludedUserId, @Param("limit") int limit);

    Optional<FriendRequest> findRequestById(String requestId);

    Optional<FriendRequest> findPendingRequestBetween(@Param("firstUserId") String firstUserId, @Param("secondUserId") String secondUserId);

    void insertRequest(FriendRequest request);

    void updateRequest(FriendRequest request);

    Optional<Friendship> findFriendship(@Param("userLowId") String userLowId, @Param("userHighId") String userHighId);

    List<Friendship> listFriendships(String userId);

    void insertFriendship(Friendship friendship);

    void deleteFriendship(@Param("userLowId") String userLowId, @Param("userHighId") String userHighId);

    List<UserSearchView> findUsersByIds(@Param("userIds") List<String> userIds);

    void recordHeartbeat(@Param("userId") String userId, @Param("seenAt") Instant seenAt);

    Optional<Instant> findLastSeen(String userId);
}
