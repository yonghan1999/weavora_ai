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
    public static final Duration ONLINE_WINDOW = Duration.ofSeconds(60);

    private final SocialRepository repository;
    private final IdentityService identityService;
    private final Clock clock;
    private final app.weavora.zhijie.controlplane.sync.ProductSync sync;

    public SocialService(SocialRepository repository, IdentityService identityService, Clock clock,
                         app.weavora.zhijie.controlplane.sync.ProductSync sync) {
        this.repository = repository;
        this.identityService = identityService;
        this.clock = clock;
        this.sync = sync;
    }

    public List<UserSearchView> search(String actorId, String query) {
        identityService.requireActive(actorId);
        if (query == null || query.isBlank()) return List.of();
        return repository.searchActiveUsers(query.trim(), actorId, 20);
    }

    public List<IncomingFriendRequest> listIncomingRequests(String actorId, String beforeId) {
        identityService.requireActive(actorId);
        if (beforeId != null && (beforeId.isBlank() || beforeId.length() > 128)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST_CURSOR", "申请分页位置无效", false);
        }
        return repository.listIncomingRequests(actorId, beforeId);
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
        notifyPair(actorId, addresseeId);
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
        notifyPair(request.requesterId(), request.addresseeId());
        return accepted;
    }

    @Transactional
    public FriendRequest reject(String actorId, String requestId) {
        FriendRequest request = pendingRequestForAddressee(actorId, requestId);
        FriendRequest rejected = respond(request, FriendRequestStatus.REJECTED, clock.instant());
        repository.updateRequest(rejected);
        notifyPair(request.requesterId(), request.addresseeId());
        return rejected;
    }

    @Transactional
    public void deleteFriend(String actorId, String friendId) {
        identityService.requireActive(actorId);
        Friendship friendship = repository.findFriendship(actorId, friendId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FRIENDSHIP_NOT_FOUND", "好友关系不存在", false));
        repository.deleteFriendship(friendship.userLowId(), friendship.userHighId());
        notifyPair(actorId, friendId);
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
            boolean online = user.accountStatus() == app.weavora.zhijie.controlplane.identity.AccountStatus.ACTIVE
                    && repository.findLastSeen(friendId).filter(lastSeen -> !lastSeen.isBefore(onlineSince)).isPresent();
            return new FriendView(user.userId(), user.email(), user.accountStatus(), online ? "ONLINE" : "OFFLINE");
        }).toList();
    }

    @Transactional
    public void heartbeat(String actorId) {
        repository.lockPresenceUser(actorId);
        identityService.requireActive(actorId);
        Instant now = clock.instant();
        boolean wasOnline = repository.findLastSeen(actorId).filter(last -> !last.isBefore(now.minus(ONLINE_WINDOW))).isPresent();
        repository.recordHeartbeat(actorId, now);
        if (!wasOnline) notifyPresenceChanged(actorId);
    }

    @Transactional
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void expirePresence() {
        // DELETE 与心跳 UPSERT 由 PostgreSQL 行锁串行；删除后只产生一次离线通知。
        for (String userId : repository.expirePresence(clock.instant().minus(ONLINE_WINDOW))) notifyPresenceChanged(userId);
    }

    private void notifyPair(String first, String second) {
        sync.userChanged(first);
        sync.userChanged(second);
    }

    private void notifyPresenceChanged(String userId) {
        for (String groupId : repository.activeGroupIds(userId)) sync.groupChanged(groupId, "member.changed");
        for (Friendship friendship : repository.listFriendships(userId)) {
            sync.userChanged(friendship.userLowId().equals(userId) ? friendship.userHighId() : friendship.userLowId());
        }
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
