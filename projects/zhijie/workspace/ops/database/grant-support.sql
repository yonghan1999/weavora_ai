-- 迁移后由迁移账号执行。支持凭据只供离线人工工具，不进入控制面进程。
BEGIN;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM zhijie_support;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM zhijie_support;
GRANT SELECT ON groups, group_members, owner_transfers, owner_recovery_requests TO zhijie_support;
GRANT SELECT (id, status) ON users TO zhijie_support;
-- SELECT FOR UPDATE 需要列 UPDATE 权限；不授权密码、会话或账号状态修改。
GRANT UPDATE (id) ON users TO zhijie_support;
GRANT UPDATE (owner_user_id) ON groups TO zhijie_support;
GRANT UPDATE (role) ON group_members TO zhijie_support;
GRANT UPDATE (status, completed_at) ON owner_transfers TO zhijie_support;
GRANT UPDATE (status, reviewer_id, decision_reason, target_user_id, decided_at) ON owner_recovery_requests TO zhijie_support;
GRANT INSERT ON audit_logs TO zhijie_support;
COMMIT;
