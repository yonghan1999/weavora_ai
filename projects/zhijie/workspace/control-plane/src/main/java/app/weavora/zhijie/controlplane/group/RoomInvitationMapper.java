package app.weavora.zhijie.controlplane.group;

import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RoomInvitationMapper {
    record Ticket(String id, String groupId, String targetRoomId, String inviterId, String inviteeEmail,
                  String inviteeId, InvitationStatus status, Instant createdAt, Instant acceptedMemberSince, Instant invalidatedAt) {}

    @Insert("INSERT INTO invitations (id, group_id, target_room_id, inviter_id, invitee_email, code_hash, status, created_at) " +
            "VALUES (#{id}, #{groupId}, #{roomId}, #{actor}, #{email}, #{digest}, 'PENDING', #{now})")
    void issue(@Param("id") String id, @Param("groupId") String groupId, @Param("roomId") String roomId,
               @Param("actor") String actor, @Param("email") String email, @Param("digest") String digest, @Param("now") Instant now);

    @Select("SELECT id, group_id, target_room_id, inviter_id, invitee_email, invitee_id, status, created_at, accepted_member_since, invalidated_at " +
            "FROM invitations WHERE code_hash=#{digest}")
    Ticket find(String digest);

    @Update("UPDATE invitations SET status='ACCEPTED', invitee_id=#{actor}, accepted_at=#{now}, accepted_member_since=#{since} " +
            "WHERE id=#{id} AND status='PENDING'")
    int accept(@Param("id") String id, @Param("actor") String actor, @Param("now") Instant now, @Param("since") Instant since);
}
