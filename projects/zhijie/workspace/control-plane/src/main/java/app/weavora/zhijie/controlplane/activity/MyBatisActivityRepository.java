package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.room.Activity;
import app.weavora.zhijie.controlplane.room.Room;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisActivityRepository implements ActivityRepository {
    private final ActivityMapper mapper;
    public MyBatisActivityRepository(ActivityMapper mapper) { this.mapper = mapper; }
    public void lockUser(String userId) { mapper.lockUser(userId); }
    public Optional<Room> findRoomForUpdate(String roomId) { return mapper.findRoomForUpdate(roomId); }
    public Optional<Activity> findActivityForUpdate(String activityId) { return mapper.findActivityForUpdate(activityId); }
    public Optional<AccountActivity> findAccountActivity(String userId) { return mapper.findAccountActivity(userId); }
    public void upsertAccountActivity(AccountActivity pointer) { mapper.upsertAccountActivity(pointer); }
    public void deleteAccountActivity(String userId) { mapper.deleteAccountActivity(userId); }
    public int countOccupied(String activityId) { return mapper.countOccupied(activityId); }
    public Optional<ActivityMember> findMember(String activityId, String userId) { return mapper.findMember(activityId, userId); }
    public List<ActivityMember> listMembers(String activityId) { return mapper.listMembers(activityId); }
    public void upsertMember(ActivityMember member) { mapper.upsertMember(member); }
    public Optional<ConnectionLease> findLatestLease(String activityId, String userId) { return mapper.findLatestLease(activityId, userId); }
    public Optional<ConnectionLease> findLease(String leaseId) { return mapper.findLease(leaseId); }
    public void insertLease(ConnectionLease lease) { mapper.insertLease(lease); }
    public void updateLease(ConnectionLease lease) { mapper.updateLease(lease); }
    public List<String> listActiveAddresses(String activityId) { return mapper.listActiveAddresses(activityId); }
    public Optional<IpAllocation> findAddress(String activityId, String userId) { return mapper.findAddress(activityId, userId); }
    public void upsertAddress(IpAllocation allocation) { mapper.upsertAddress(allocation); }
    public void releaseAddress(String activityId, String userId, Instant releasedAt) { mapper.releaseAddress(activityId, userId, releasedAt); }
    public Optional<IpHistory> findIpHistory(String roomId, String userId) { return mapper.findIpHistory(roomId, userId); }
    public void upsertIpHistory(IpHistory history) { mapper.upsertIpHistory(history); }
    public List<ActivityMember> listExpiredGrace(Instant now) { return mapper.listExpiredGrace(now); }
    public String findGroupId(String activityId) { return mapper.findGroupId(activityId); }
    public List<ActivityMember> listActiveByGroup(String groupId) { return mapper.listActiveByGroup(groupId); }
}
