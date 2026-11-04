package app.weavora.zhijie.controlplane.room;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RoomController {
    private final RoomService rooms;
    private final IdentityService identity;

    public RoomController(RoomService rooms, IdentityService identity) {
        this.rooms = rooms;
        this.identity = identity;
    }

    @PostMapping("/groups/{groupId}/rooms")
    public Map<String, RoomView> create(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                        @Valid @RequestBody RoomBody body) {
        return data(rooms.createRoom(actor(auth), groupId, body.name(), body.description(), body.gameName(), body.password()));
    }

    @GetMapping("/groups/{groupId}/rooms")
    public Map<String, List<RoomView>> list(@RequestHeader("Authorization") String auth, @PathVariable String groupId) {
        return Map.of("data", rooms.listRooms(actor(auth), groupId).stream().map(RoomView::from).toList());
    }

    @GetMapping("/rooms/{roomId}")
    public Map<String, RoomView> get(@RequestHeader("Authorization") String auth, @PathVariable String roomId) {
        return data(rooms.getRoom(actor(auth), roomId));
    }

    @PatchMapping("/rooms/{roomId}")
    public Map<String, RoomView> update(@RequestHeader("Authorization") String auth, @PathVariable String roomId,
                                        @Valid @RequestBody RoomBody body) {
        return data(rooms.updateRoom(actor(auth), roomId, body.name(), body.description(), body.gameName(), body.password()));
    }

    @PostMapping("/rooms/{roomId}/open")
    public Map<String, RoomView> open(@RequestHeader("Authorization") String auth, @PathVariable String roomId) {
        return data(rooms.setOpenState(actor(auth), roomId, RoomOpenState.OPEN));
    }

    @PostMapping("/rooms/{roomId}/pause")
    public Map<String, RoomView> pause(@RequestHeader("Authorization") String auth, @PathVariable String roomId) {
        return data(rooms.setOpenState(actor(auth), roomId, RoomOpenState.PAUSED));
    }

    @PostMapping("/rooms/{roomId}/owner")
    public Map<String, RoomView> assignOwner(@RequestHeader("Authorization") String auth, @PathVariable String roomId,
                                             @Valid @RequestBody OwnerBody body) {
        return data(rooms.assignOwner(actor(auth), roomId, body.targetUserId()));
    }

    @DeleteMapping("/rooms/{roomId}")
    public Map<String, Object> delete(@RequestHeader("Authorization") String auth, @PathVariable String roomId) {
        rooms.deleteRoom(actor(auth), roomId);
        return Map.of("data", Map.of("deleted", true));
    }

    @PostMapping("/rooms/{roomId}/activities")
    public Map<String, Activity> start(@RequestHeader("Authorization") String auth, @PathVariable String roomId) {
        return Map.of("data", rooms.startActivity(actor(auth), roomId));
    }

    @PostMapping("/activities/{activityId}/end")
    public Map<String, Object> end(@RequestHeader("Authorization") String auth, @PathVariable String activityId) {
        rooms.endActivity(actor(auth), activityId);
        return Map.of("data", Map.of("ended", true));
    }

    private Map<String, RoomView> data(Room room) { return Map.of("data", RoomView.from(room)); }
    private String actor(String authorization) { return identity.authenticate(authorization).id(); }

    public record RoomBody(@NotBlank String name, String description, String gameName, String password) {
    }
    public record OwnerBody(@NotBlank String targetUserId) {
    }
}
