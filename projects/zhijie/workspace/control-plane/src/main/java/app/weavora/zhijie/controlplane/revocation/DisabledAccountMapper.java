package app.weavora.zhijie.controlplane.revocation;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DisabledAccountMapper {
    record Candidate(String groupId, String userId) { }

    @Select("""
            SELECT DISTINCT g.id AS group_id, u.id AS user_id
            FROM users u JOIN group_members m ON m.user_id = u.id JOIN groups g ON g.id = m.group_id
            WHERE u.status = 'DISABLED' AND g.status = 'ACTIVE' AND (
                (m.status = 'ACTIVE' AND m.role <> 'OWNER')
                OR EXISTS (SELECT 1 FROM rooms r WHERE r.group_id = g.id AND r.owner_user_id = u.id
                           AND r.owner_user_id <> g.owner_user_id AND r.lifecycle = 'AVAILABLE')
                OR EXISTS (SELECT 1 FROM activity_members am JOIN activities a ON a.id = am.activity_id
                           JOIN rooms r ON r.id = a.room_id WHERE r.group_id = g.id AND am.user_id = u.id
                           AND am.state IN ('ACTIVE', 'GRACE')))
            ORDER BY group_id, user_id LIMIT 100
            """)
    List<Candidate> candidates();

    @Select("SELECT status FROM users WHERE id = #{userId}")
    String status(String userId);

    @Select("SELECT DISTINCT user_id FROM auth_sessions s JOIN users u ON u.id = s.user_id WHERE u.status = 'DISABLED' AND s.revoked_at IS NULL ORDER BY user_id LIMIT 100")
    List<String> sessionsToRevoke();
}
