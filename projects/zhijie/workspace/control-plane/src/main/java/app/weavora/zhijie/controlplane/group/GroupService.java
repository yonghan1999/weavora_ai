package app.weavora.zhijie.controlplane.group;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.audit.AuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {
    private final GroupRepository repository;
    private final IdentityService identityService;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditRecorder audit;
    private final app.weavora.zhijie.controlplane.sync.ProductSync sync;

    public GroupService(GroupRepository repository, IdentityService identityService, Clock clock, ApplicationEventPublisher events, AuditRecorder audit,
                        app.weavora.zhijie.controlplane.sync.ProductSync sync) {
        this.repository = repository;
        this.identityService = identityService;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.sync = sync;
    }

    @Transactional
    public Group createGroup(String actorId, String name, String avatar, String description) {
        identityService.requireActive(actorId);
        requireName(name);
        Group group = new Group(UUID.randomUUID().toString(), name.trim(), avatar, description == null ? "" : description,
                actorId, GroupStatus.ACTIVE, clock.instant(), null);
        repository.insertGroup(group);
        repository.insertMember(new GroupMember(group.id(), actorId, GroupRole.OWNER, MemberStatus.ACTIVE, group.createdAt(), null));
        record(group.id(), actorId, "GROUP", group.id(), "GROUP_CREATE");
        return group;
    }

    public List<Group> listGroups(String actorId) {
        identityService.requireActive(actorId);
        return repository.listGroupsForUser(actorId);
    }

    public Group getGroup(String actorId, String groupId) {
        requireMembership(groupId, actorId);
        return activeGroup(groupId);
    }

    public List<GroupMemberView> listMembers(String actorId, String groupId) {
        requireMembership(groupId, actorId);
        return repository.listVisibleMembers(groupId, clock.instant().minus(app.weavora.zhijie.controlplane.social.SocialService.ONLINE_WINDOW));
    }

    @Transactional
    public Group updateGroup(String actorId, String groupId, String name, String avatar, String description) {
        lockGroup(groupId);
        requireRole(groupId, actorId, GroupRole.OWNER, GroupRole.ADMIN);
        requireName(name);
        Group group = activeGroup(groupId);
        Group updated = new Group(group.id(), name.trim(), avatar, description == null ? "" : description,
                group.ownerUserId(), group.status(), group.createdAt(), group.dissolvedAt());
        repository.updateGroup(updated);
        record(groupId, actorId, "GROUP", groupId, "GROUP_UPDATE");
        return updated;
    }

    @Transactional
    public GroupInvitation invite(String actorId, String groupId, String inviteeId) {
        lockGroup(groupId);
        requireMembership(groupId, actorId);
        identityService.requireActive(inviteeId);
        GroupMember existing = repository.findMember(groupId, inviteeId).orElse(null);
        if (existing != null && existing.status() == MemberStatus.ACTIVE) {
            throw conflict("GROUP_MEMBER_EXISTS", "被邀请者已是群成员");
        }
        if (repository.findPendingInvitation(groupId, inviteeId).isPresent()) {
            throw conflict("GROUP_INVITATION_PENDING", "已有发给该账号的待接受邀请");
        }
        GroupInvitation invitation = new GroupInvitation(UUID.randomUUID().toString(), groupId, actorId, inviteeId,
                InvitationStatus.PENDING, clock.instant(), null, null, null);
        repository.insertInvitation(invitation);
        record(groupId, actorId, "INVITATION", invitation.id(), "GROUP_INVITE");
        sync.userChanged(inviteeId);
        return invitation;
    }

    @Transactional
    public GroupInvitation revokeInvitation(String actorId, String groupId, String invitationId) {
        lockGroup(groupId);
        requireRole(groupId, actorId, GroupRole.OWNER, GroupRole.ADMIN);
        GroupInvitation invitation = repository.findInvitation(invitationId).orElseThrow(this::invitationUnavailable);
        if (!invitation.groupId().equals(groupId) || invitation.status() != InvitationStatus.PENDING) {
            throw invitationUnavailable();
        }
        GroupInvitation revoked = new GroupInvitation(invitation.id(), invitation.groupId(), invitation.inviterId(),
                invitation.inviteeId(), InvitationStatus.REVOKED, invitation.createdAt(), invitation.acceptedAt(),
                clock.instant(), invitation.invalidatedAt());
        repository.updateInvitation(revoked);
        record(groupId, actorId, "INVITATION", invitationId, "GROUP_INVITATION_REVOKE");
        if (invitation.inviteeId() != null) sync.userChanged(invitation.inviteeId());
        return revoked;
    }

    public List<PendingGroupInvitation> listIncomingInvitations(String actorId, String beforeId) {
        identityService.requireActive(actorId);
        validateInvitationCursor(beforeId);
        return repository.listIncomingInvitations(actorId, beforeId);
    }

    public List<PendingGroupInvitation> listGroupInvitations(String actorId, String groupId, String beforeId) {
        requireRole(groupId, actorId, GroupRole.OWNER, GroupRole.ADMIN);
        validateInvitationCursor(beforeId);
        return repository.listGroupInvitations(groupId, beforeId);
    }

    private void validateInvitationCursor(String beforeId) {
        if (beforeId != null && (beforeId.isBlank() || beforeId.length() > 128)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INVITATION_CURSOR", "邀请列表游标无效", false);
        }
    }

    @Transactional
    public GroupInvitation acceptInvitation(String actorId, String invitationId) {
        identityService.requireActive(actorId);
        GroupInvitation invitation = repository.findInvitation(invitationId).orElseThrow(this::invitationUnavailable);
        if (!actorId.equals(invitation.inviteeId())) throw invitationUnavailable();
        lockGroup(invitation.groupId());
        invitation = repository.findInvitation(invitationId).orElseThrow(this::invitationUnavailable);
        activeGroup(invitation.groupId());
        GroupMember existing = repository.findMember(invitation.groupId(), actorId).orElse(null);
        if (invitation.status() == InvitationStatus.ACCEPTED) {
            if (invitation.invalidatedAt() == null && existing != null && existing.status() == MemberStatus.ACTIVE
                    && existing.joinedAt().equals(invitation.acceptedAt())) return invitation;
            throw invitationUnavailable();
        }
        if (invitation.status() != InvitationStatus.PENDING) throw invitationUnavailable();
        requireMembership(invitation.groupId(), invitation.inviterId());
        Instant now = clock.instant();
        ensureInvitedMembership(invitation.groupId(), actorId, existing, now);
        GroupInvitation accepted = new GroupInvitation(invitation.id(), invitation.groupId(), invitation.inviterId(),
                actorId, InvitationStatus.ACCEPTED, invitation.createdAt(), now, invitation.revokedAt(), invitation.invalidatedAt());
        repository.updateInvitation(accepted);
        record(invitation.groupId(), actorId, "INVITATION", invitationId, "GROUP_INVITATION_ACCEPT");
        return accepted;
    }

    // 普通邀请和房间邀请码共用群籍写入规则；调用者已锁群并校验受邀身份。
    Instant ensureInvitedMembership(String groupId, String actorId, GroupMember existing, Instant now) {
        if (existing != null && existing.status() == MemberStatus.ACTIVE) return existing.joinedAt();
        if (existing != null) repository.expireAcceptedInvitations(groupId, actorId, now);
        repository.insertMember(new GroupMember(groupId, actorId, GroupRole.MEMBER, MemberStatus.ACTIVE, now, null));
        return now;
    }

    @Transactional
    public void changeRole(String actorId, String groupId, String targetUserId, GroupRole newRole) {
        lockGroup(groupId);
        requireRole(groupId, actorId, GroupRole.OWNER);
        if (newRole == GroupRole.OWNER || targetUserId.equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CHANGE_NOT_ALLOWED", "请使用群主转让流程", false);
        }
        GroupMember target = requireMembership(groupId, targetUserId);
        repository.updateMember(new GroupMember(target.groupId(), target.userId(), newRole, target.status(), target.joinedAt(), null));
        record(groupId, actorId, "GROUP_MEMBER", targetUserId, newRole == GroupRole.ADMIN ? "GROUP_ADMIN_GRANT" : "GROUP_ADMIN_REVOKE");
    }

    @Transactional
    public void removeMember(String actorId, String groupId, String targetUserId) {
        lockGroup(groupId);
        GroupMember actor = requireMembership(groupId, actorId);
        GroupMember target = requireMembership(groupId, targetUserId);
        if (target.role() == GroupRole.OWNER || actorId.equals(targetUserId)
                || actor.role() == GroupRole.MEMBER || (actor.role() == GroupRole.ADMIN && target.role() != GroupRole.MEMBER)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前角色不能移除该成员", false);
        }
        deactivateMember(target, MemberStatus.REMOVED);
        record(groupId, actorId, "GROUP_MEMBER", targetUserId, "GROUP_MEMBER_REMOVE");
    }

    @Transactional
    public void leaveGroup(String actorId, String groupId) {
        lockGroup(groupId);
        GroupMember member = requireMembership(groupId, actorId);
        if (member.role() == GroupRole.OWNER) {
            throw conflict("OWNER_MUST_TRANSFER_OR_DISSOLVE", "群主退出前必须完成转让或解散");
        }
        deactivateMember(member, MemberStatus.LEFT);
        record(groupId, actorId, "GROUP_MEMBER", actorId, "GROUP_LEAVE");
    }

    @Transactional
    public OwnerTransfer initiateTransfer(String actorId, String groupId, String targetUserId) {
        lockGroup(groupId);
        requireRole(groupId, actorId, GroupRole.OWNER);
        requireMembership(groupId, targetUserId);
        if (actorId.equals(targetUserId)) throw conflict("OWNER_TRANSFER_INVALID", "不能转让给自己");
        repository.findPendingTransfer(groupId).ifPresent(existing -> {
            throw conflict("OWNER_TRANSFER_PENDING", "已有待接受的转让");
        });
        OwnerTransfer transfer = new OwnerTransfer(UUID.randomUUID().toString(), groupId, actorId, targetUserId,
                GovernanceStatus.PENDING, clock.instant(), null);
        repository.insertTransfer(transfer);
        record(groupId, actorId, "OWNER_TRANSFER", transfer.id(), "OWNER_TRANSFER_CREATE");
        return transfer;
    }

    public List<OwnerTransfer> listPendingTransfers(String actorId, String groupId) {
        requireMembership(groupId, actorId);
        return repository.findPendingTransfer(groupId)
                .filter(transfer -> transfer.fromUserId().equals(actorId) || transfer.toUserId().equals(actorId))
                .stream().toList();
    }

    @Transactional
    public OwnerTransfer acceptTransfer(String actorId, String transferId) {
        OwnerTransfer transfer = findPendingTransferById(transferId);
        lockGroup(transfer.groupId());
        transfer = findPendingTransferById(transferId);
        if (!transfer.toUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "只有指定成员可接受转让", false);
        }
        GroupMember previousOwner = requireMembership(transfer.groupId(), transfer.fromUserId());
        GroupMember newOwner = requireMembership(transfer.groupId(), actorId);
        Group group = activeGroup(transfer.groupId());
        if (previousOwner.role() != GroupRole.OWNER || !group.ownerUserId().equals(previousOwner.userId())) {
            throw conflict("OWNER_TRANSFER_INVALID", "原群主状态已变化");
        }
        Instant now = clock.instant();
        repository.updateMember(new GroupMember(previousOwner.groupId(), previousOwner.userId(), GroupRole.MEMBER,
                MemberStatus.ACTIVE, previousOwner.joinedAt(), null));
        repository.updateMember(new GroupMember(newOwner.groupId(), newOwner.userId(), GroupRole.OWNER,
                MemberStatus.ACTIVE, newOwner.joinedAt(), null));
        repository.updateGroup(new Group(group.id(), group.name(), group.avatar(), group.description(), actorId,
                group.status(), group.createdAt(), group.dissolvedAt()));
        OwnerTransfer accepted = new OwnerTransfer(transfer.id(), transfer.groupId(), transfer.fromUserId(), actorId,
                GovernanceStatus.ACCEPTED, transfer.createdAt(), now);
        repository.updateTransfer(accepted);
        record(transfer.groupId(), actorId, "OWNER_TRANSFER", transferId, "OWNER_TRANSFER_ACCEPT");
        return accepted;
    }

    @Transactional
    public void cancelTransfer(String actorId, String transferId) {
        OwnerTransfer transfer = findPendingTransferById(transferId);
        lockGroup(transfer.groupId());
        transfer = findPendingTransferById(transferId);
        requireRole(transfer.groupId(), actorId, GroupRole.OWNER);
        repository.updateTransfer(new OwnerTransfer(transfer.id(), transfer.groupId(), transfer.fromUserId(),
                transfer.toUserId(), GovernanceStatus.CANCELLED, transfer.createdAt(), clock.instant()));
        record(transfer.groupId(), actorId, "OWNER_TRANSFER", transferId, "OWNER_TRANSFER_CANCEL");
    }

    @Transactional
    public OwnerRecoveryRequest requestRecovery(String actorId, String groupId, String evidenceRef) {
        lockGroup(groupId);
        identityService.requireActive(actorId);
        Group group = activeGroup(groupId);
        if (evidenceRef == null || evidenceRef.isBlank() || evidenceRef.length() > 512) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_REQUIRED", "必须提供人工审核证据引用", false);
        }
        OwnerRecoveryRequest request = new OwnerRecoveryRequest(UUID.randomUUID().toString(), groupId, actorId,
                evidenceRef, GovernanceStatus.PENDING, null, null, clock.instant(), group.ownerUserId());
        repository.insertRecoveryRequest(request);
        record(groupId, actorId, "OWNER_RECOVERY", request.id(), "OWNER_RECOVERY_REQUEST");
        return request;
    }

    @Transactional
    public void dissolveGroup(String actorId, String groupId) {
        lockGroup(groupId);
        requireRole(groupId, actorId, GroupRole.OWNER);
        Group group = activeGroup(groupId);
        Instant now = clock.instant();
        repository.updateGroup(new Group(group.id(), group.name(), group.avatar(), group.description(), group.ownerUserId(),
                GroupStatus.DISSOLVED, group.createdAt(), now));
        for (GroupMember member : repository.listActiveMembers(groupId)) sync.userChanged(member.userId());
        repository.deactivateAllMembers(groupId, now);
        repository.invalidateAllPendingInvitations(groupId, now);
        repository.cancelPendingTransfers(groupId, now);
        events.publishEvent(new GroupDissolvedEvent(groupId));
        record(groupId, actorId, "GROUP", groupId, "GROUP_DISSOLVE");
    }

    public GroupMember requireMembership(String groupId, String userId) {
        identityService.requireActive(userId);
        GroupMember member = repository.findMember(groupId, userId).orElseThrow(this::groupUnavailable);
        if (member.status() != MemberStatus.ACTIVE) throw groupUnavailable();
        activeGroup(groupId);
        return member;
    }

    // 仅供持有群锁与账号锁的失效清理调用；群主归属保留给人工恢复流程。
    public void invalidateDisabledMember(String groupId, String userId) {
        GroupMember member = repository.findMember(groupId, userId).orElse(null);
        if (member != null && member.status() == MemberStatus.ACTIVE && member.role() != GroupRole.OWNER) {
            deactivateMember(member, MemberStatus.REMOVED);
            record(groupId, userId, "GROUP_MEMBER", userId, "ACCOUNT_MEMBERSHIP_REVOKE");
        } else if (member != null && member.role() != GroupRole.OWNER) {
            events.publishEvent(new app.weavora.zhijie.controlplane.room.GroupMemberStatusEvent(
                    groupId, userId, activeGroup(groupId).ownerUserId(), MemberStatus.REMOVED));
        }
    }

    private GroupMember requireRole(String groupId, String userId, GroupRole... roles) {
        GroupMember member = requireMembership(groupId, userId);
        for (GroupRole role : roles) if (member.role() == role) return member;
        throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前群角色无权执行此操作", false);
    }

    private Group activeGroup(String groupId) {
        Group group = repository.findGroup(groupId).orElseThrow(this::groupUnavailable);
        if (group.status() != GroupStatus.ACTIVE) throw groupUnavailable();
        return group;
    }

    // 调用者须位于事务中；群与房间治理共用同一把群记录锁。
    public void lockGroup(String groupId) {
        repository.lockGroup(groupId).orElseThrow(this::groupUnavailable);
    }

    private void deactivateMember(GroupMember member, MemberStatus status) {
        Instant now = clock.instant();
        repository.updateMember(new GroupMember(member.groupId(), member.userId(), GroupRole.MEMBER, status, member.joinedAt(), now));
        repository.findPendingTransfer(member.groupId()).filter(transfer -> transfer.toUserId().equals(member.userId()))
                .ifPresent(transfer -> repository.cancelPendingTransfers(member.groupId(), now));
        for (GroupInvitation invitation : repository.pendingInvalidatedByDeparture(member.groupId(), member.userId())) {
            repository.updateInvitation(new GroupInvitation(invitation.id(), invitation.groupId(), invitation.inviterId(),
                    invitation.inviteeId(), InvitationStatus.INVALIDATED, invitation.createdAt(), invitation.acceptedAt(),
                    invitation.revokedAt(), now));
        }
        String groupOwnerId = activeGroup(member.groupId()).ownerUserId();
        events.publishEvent(new app.weavora.zhijie.controlplane.room.GroupMemberStatusEvent(
                member.groupId(), member.userId(), groupOwnerId, status));
        sync.userChanged(member.userId());
    }

    private OwnerTransfer findPendingTransferById(String transferId) {
        return repository.findPendingTransferById(transferId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "OWNER_TRANSFER_NOT_AVAILABLE", "转让不存在或已结束", false));
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "群名称不能为空", false);
        }
    }

    void record(String groupId, String actorId, String targetType, String targetId, String action) {
        audit.record(groupId, actorId, targetType, targetId, action, "SUCCESS", UUID.randomUUID().toString());
        sync.groupChanged(groupId, targetType.equals("GROUP_MEMBER") || action.equals("GROUP_INVITATION_ACCEPT")
                ? "member.changed" : "group.changed");
    }

    private ApiException groupUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_AVAILABLE", "群组不可用", false);
    }

    private ApiException invitationUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_AVAILABLE", "邀请不可用", false);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, false);
    }
}
