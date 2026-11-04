package app.weavora.zhijie.controlplane.room;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoomMapper {
    void insertRoom(Room room);
    Optional<Room> findRoom(String roomId);
    List<Room> listGroupRooms(String groupId);
    void updateRoom(Room room);
    void insertActivity(Activity activity);
    Optional<Activity> findActivity(String activityId);
    Optional<Activity> findActiveActivityByRoom(String roomId);
    void updateActivity(Activity activity);
}
