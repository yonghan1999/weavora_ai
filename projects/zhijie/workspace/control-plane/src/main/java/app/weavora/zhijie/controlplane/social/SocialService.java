package app.weavora.zhijie.controlplane.social;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SocialService {
    static final Duration ONLINE_WINDOW = Duration.ofSeconds(60);

    private final SocialRepository repository;
    private final IdentityService identityService;
    private final Clock clock;

    public SocialService(SocialRepository repository, IdentityService identityService, Clock clock) {
        this.repository = repository;
        this.identityService = identityService;
        this.clock = clock;
    }

    public List<UserSearchView> search(String actorId, String query) {
        identityService.requireActive(actorId);
        if (query == null || query.isBlank()) return List.of();
        return repository.searchActiveUsers(query.trim(), actorId, 20);
    }

    @Transactional
    public FriendRequest sendRequest(String actorId, String addresseeId) {
        identityService.requireActive(actorId);
        identityService.requireActive(addresseeId);
        if (actorId.equals(addresseeId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SELF_FRIEND_REQUEST", "不能添加自己为好友", false);
        }
        if (repository.findFriendship(actorId, addresseeId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "FRIENDSHIP_EXISTS", "已经是好友", false);
        }
        repository.findPendingRequestBetween(actorId, addresseeId).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "FRIEND_REQUEST_PENDING", "存在待处理的好友申请", false);
        });
        FriendRequest request = new FriendRequest(
                UUID.randomUUID().toString(), actorId, addresseeId, FriendRequestStatus.PENDING, clock.instant(), null
        );
        repository.insertRequest(request);
        return request;
    }

    @Transactional
    public FriendRequest accept(String actorId, String requestId) {
        FriendRequest request = pendingRequestForAddressee(actorId, requestId);
        identityService.requireActive(request.requesterId());
        Instant now = clock.instant();
        Friendship friendship = new Friendship(
                UUID.randomUUID().toString(), Friendship.low(request.requesterId(), request.addresseeId()),
                Friendship.high(request.requesterId(), request.addresseeId()), now
        );
        repository.insertFriendship(friendship);
        FriendRequest accepted = respond(request, FriendRequestStatus.ACCEPTED, now);
        repository.updateRequest(accepted);
        return accepted;
    }

    @Transactional
    public FriendRequest reject(String actorId, String requestId) {
        FriendRequest request = pendingRequestForAddressee(actorId, requestId);
        FriendRequest rejected = respond(request, FriendRequestStatus.REJECTED, clock.instant());
        repository.updateRequest(rejected);
        return rejected;
    }

    @Transactional
    public void deleteFriend(String actorId, String friendId) {
        identityService.requireActive(actorId);
        Friendship friendship = repository.findFriendship(actorId, friendId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FRIENDSHIP_NOT_FOUND", "好友关系不存在", false));
        repository.deleteFriendship(friendship.userLowId(), friendship.userHighId());
    }

    public List<FriendView> listFriends(String actorId) {
        identityService.requireActive(actorId);
        List<String> friendIds = repository.listFriendships(actorId).stream()
                .map(item -> item.userLowId().equals(actorId) ? item.userHighId() : item.userLowId()).toList();
        Map<String, UserSearchView> users = repository.findUsersByIds(friendIds).stream()
                .collect(Collectors.toMap(UserSearchView::userId, Function.identity()));
        Instant onlineSince = clock.instant().minus(ONLINE_WINDOW);
        return friendIds.stream().map(friendId -> {
            UserSearchView user = users.get(friendId);
            boolean online = repository.findLastSeen(friendId).filter(lastSeen -> !lastSeen.isBefore(onlineSince)).isPresent();
            return new FriendView(user.userId(), user.email(), user.accountStatus(), online ? "ONLINE" : "OFFLINE");
        }).toList();
    }

    public void heartbeat(String actorId) {
        identityService.requireActive(actorId);
        repository.recordHeartbeat(actorId, clock.instant());
    }

    private FriendRequest pendingRequestForAddressee(String actorId, String requestId) {
        identityService.requireActive(actorId);
        FriendRequest request = repository.findRequestById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FRIEND_REQUEST_NOT_FOUND", "好友申请不存在", false));
        if (!request.addresseeId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "只能处理发给自己的申请", false);
        }
        if (request.status() != FriendRequestStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "FRIEND_REQUEST_CLOSED", "好友申请已处理", false);
        }
        return request;
    }

    private FriendRequest respond(FriendRequest request, FriendRequestStatus status, Instant now) {
        return new FriendRequest(request.id(), request.requesterId(), request.addresseeId(), status, request.createdAt(), now);
    }
}
