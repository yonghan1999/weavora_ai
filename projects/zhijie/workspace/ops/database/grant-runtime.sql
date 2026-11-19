-- 以迁移账号在每次成功迁移后执行；新增业务表必须显式评审并加入清单。
BEGIN;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM zhijie_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM zhijie_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    users, email_challenges, auth_sessions, friend_requests, friendships, presence,
    groups, group_members, invitations, owner_transfers,
    messages, rooms, activities, activity_members, account_activity, connection_leases,
    ip_allocations, ip_history, outbox_events, activity_grants,
    network_peer_endpoints
TO zhijie_app;
GRANT SELECT ON owner_recovery_requests TO zhijie_app;
GRANT INSERT (id, group_id, requester_id, evidence_ref, created_at, expected_owner_user_id)
ON owner_recovery_requests TO zhijie_app;
GRANT SELECT, INSERT ON audit_logs TO zhijie_app;
GRANT SELECT, INSERT ON network_policy_revisions TO zhijie_app;
GRANT SELECT, INSERT ON sync_event_sequences TO zhijie_app;
GRANT SELECT ON flyway_schema_history TO zhijie_app;
GRANT USAGE ON SEQUENCE outbox_events_sequence_seq TO zhijie_app;
GRANT USAGE ON SEQUENCE sync_event_sequences_sequence_seq TO zhijie_app;
COMMIT;
