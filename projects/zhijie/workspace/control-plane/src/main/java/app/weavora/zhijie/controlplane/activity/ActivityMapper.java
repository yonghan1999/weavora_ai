package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.room.Activity;
import app.weavora.zhijie.controlplane.room.Room;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityMapper {
    void lockUser(String userId);
    Optional<Room> findRoomForUpdate(String roomId);
    Optional<Activity> findActivityForUpdate(String activityId);
    Optional<AccountActivity> findAccountActivity(String userId);
    void upsertAccountActivity(AccountActivity pointer);
    void deleteAccountActivity(String userId);
    int countOccupied(String activityId);
    Optional<ActivityMember> findMember(@Param("activityId") String activityId, @Param("userId") String userId);
    List<ActivityMember> listMembers(String activityId);
    void upsertMember(ActivityMember member);
    Optional<ConnectionLease> findLatestLease(@Param("activityId") String activityId, @Param("userId") String userId);
    Optional<ConnectionLease> findLease(String leaseId);
    void insertLease(ConnectionLease lease);
    void updateLease(ConnectionLease lease);
    List<String> listActiveAddresses(String activityId);
    Optional<IpAllocation> findAddress(@Param("activityId") String activityId, @Param("userId") String userId);
    void upsertAddress(IpAllocation allocation);
    void releaseAddress(@Param("activityId") String activityId, @Param("userId") String userId, @Param("releasedAt") Instant releasedAt);
    Optional<IpHistory> findIpHistory(@Param("roomId") String roomId, @Param("userId") String userId);
    void upsertIpHistory(IpHistory history);
    List<ActivityMember> listExpiredGrace(Instant now);
    String findGroupId(String activityId);
    List<ActivityMember> listActiveByGroup(String groupId);
}
