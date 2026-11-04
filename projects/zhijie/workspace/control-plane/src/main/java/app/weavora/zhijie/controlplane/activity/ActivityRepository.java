package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.room.Activity;
import app.weavora.zhijie.controlplane.room.Room;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository {
    void lockUser(String userId);
    Optional<Room> findRoomForUpdate(String roomId);
    Optional<Activity> findActivityForUpdate(String activityId);
    Optional<AccountActivity> findAccountActivity(String userId);
    void upsertAccountActivity(AccountActivity pointer);
    void deleteAccountActivity(String userId);
    int countOccupied(String activityId);
    Optional<ActivityMember> findMember(String activityId, String userId);
    List<ActivityMember> listMembers(String activityId);
    void upsertMember(ActivityMember member);
    Optional<ConnectionLease> findLatestLease(String activityId, String userId);
    Optional<ConnectionLease> findLease(String leaseId);
    void insertLease(ConnectionLease lease);
    void updateLease(ConnectionLease lease);
    List<String> listActiveAddresses(String activityId);
    Optional<IpAllocation> findAddress(String activityId, String userId);
    void upsertAddress(IpAllocation allocation);
    void releaseAddress(String activityId, String userId, Instant releasedAt);
    Optional<IpHistory> findIpHistory(String roomId, String userId);
    void upsertIpHistory(IpHistory history);
    List<ActivityMember> listExpiredGrace(Instant now);
    String findGroupId(String activityId);
    List<ActivityMember> listActiveByGroup(String groupId);
}
