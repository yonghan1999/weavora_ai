package app.weavora.zhijie.controlplane.group;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomInvitationService {
    private final RoomInvitationMapper codes;
    private final GroupService groups;
    private final GroupRepository members;
    private final RoomService rooms;
    private final RoomRepository roomRepository;
    private final IdentityService identity;
    private final CredentialProtector secrets;
    private final Clock clock;

    public RoomInvitationService(RoomInvitationMapper codes, GroupService groups, GroupRepository members, RoomService rooms,
            RoomRepository roomRepository, IdentityService identity, CredentialProtector secrets, Clock clock) {
        this.codes=codes; this.groups=groups; this.members=members; this.rooms=rooms; this.roomRepository=roomRepository;
        this.identity=identity; this.secrets=secrets; this.clock=clock;
    }

    @Transactional
    public String issue(String actor, String roomId, String email) {
        var room = rooms.getRoom(actor, roomId);
        groups.lockGroup(room.groupId());
        room = rooms.getRoom(actor, roomId);
        String code = secrets.randomSecret(), id = UUID.randomUUID().toString();
        codes.issue(id, room.groupId(), room.id(), actor, IdentityService.normalizeEmail(email), digest(code), clock.instant());
        groups.record(room.groupId(), actor, "INVITATION", id, "GROUP_INVITE");
        return code;
    }

    @Transactional
    public String redeem(String actor, String code) {
        var user = identity.requireActive(actor);
        String digest = digest(code);
        var ticket = codes.find(digest);
        if (ticket == null || !user.emailNormalized().equals(ticket.inviteeEmail())) throw unavailable();
        groups.lockGroup(ticket.groupId());
        ticket = codes.find(digest);
        if (ticket == null || ticket.invalidatedAt() != null) throw unavailable();
        var room = roomRepository.findRoom(ticket.targetRoomId()).orElseThrow(this::unavailable);
        if (!room.groupId().equals(ticket.groupId()) || room.lifecycle() != RoomLifecycle.AVAILABLE) throw unavailable();
        var member = members.findMember(room.groupId(), actor).orElse(null);
        if (ticket.status() == InvitationStatus.ACCEPTED) {
            if (!actor.equals(ticket.inviteeId()) || member == null || member.status()!=MemberStatus.ACTIVE
                    || !member.joinedAt().equals(ticket.acceptedMemberSince())) throw unavailable();
            groups.requireMembership(room.groupId(), actor);
            return room.id();
        }
        if (ticket.status() != InvitationStatus.PENDING || (member != null && member.leftAt() != null
                && !member.leftAt().isBefore(ticket.createdAt()))) throw unavailable();
        groups.requireMembership(room.groupId(), ticket.inviterId());
        var now = clock.instant();
        var since = groups.ensureInvitedMembership(room.groupId(), actor, member, now);
        if (codes.accept(ticket.id(), actor, now, since) != 1) throw unavailable();
        groups.record(room.groupId(), actor, "INVITATION", ticket.id(), "GROUP_INVITATION_ACCEPT");
        return room.id(); // 仅定位房间；密码、容量及切房仍由原入房事务执行。
    }

    private String digest(String code) { return secrets.hashToken("room-invite:" + code); }
    private ApiException unavailable() { return new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_AVAILABLE", "邀请不可用", false); }
}
