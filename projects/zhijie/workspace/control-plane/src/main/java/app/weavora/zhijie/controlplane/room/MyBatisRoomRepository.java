package app.weavora.zhijie.controlplane.room;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisRoomRepository implements RoomRepository {
    private final RoomMapper mapper;

    public MyBatisRoomRepository(RoomMapper mapper) { this.mapper = mapper; }
    public void insertRoom(Room room) { mapper.insertRoom(room); }
    public Optional<Room> findRoom(String roomId) { return mapper.findRoom(roomId); }
    public List<Room> listGroupRooms(String groupId) { return mapper.listGroupRooms(groupId); }
    public void updateRoom(Room room) { mapper.updateRoom(room); }
    public void insertActivity(Activity activity) { mapper.insertActivity(activity); }
    public Optional<Activity> findActivity(String activityId) { return mapper.findActivity(activityId); }
    public Optional<Activity> findActiveActivityByRoom(String roomId) { return mapper.findActiveActivityByRoom(roomId); }
    public void updateActivity(Activity activity) { mapper.updateActivity(activity); }
}
