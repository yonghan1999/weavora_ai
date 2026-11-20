package app.weavora.zhijie.controlplane.revocation;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ActivitySessionMapper {
    @Select("SELECT a.user_id FROM account_activity a LEFT JOIN auth_sessions s ON s.id=a.auth_session_id "
            + "WHERE s.id IS NULL OR s.user_id<>a.user_id OR s.device_id<>a.device_id OR s.revoked_at IS NOT NULL "
            + "OR s.expires_at<=#{now} ORDER BY a.user_id LIMIT 100")
    List<String> invalidUsers(Instant now);
}
