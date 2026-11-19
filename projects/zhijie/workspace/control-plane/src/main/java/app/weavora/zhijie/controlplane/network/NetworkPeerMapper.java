package app.weavora.zhijie.controlplane.network;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NetworkPeerMapper {
    @Insert("""
            INSERT INTO network_peer_endpoints (activity_id, user_id, grant_id, member_generation, endpoint, updated_at)
            VALUES (#{activityId}, #{userId}, #{grantId}, #{generation}, #{endpoint}, #{now})
            ON CONFLICT (activity_id, user_id) DO UPDATE SET grant_id = EXCLUDED.grant_id,
                member_generation = EXCLUDED.member_generation, endpoint = EXCLUDED.endpoint, updated_at = EXCLUDED.updated_at
            """)
    void register(@Param("activityId") String activityId, @Param("userId") String userId,
                  @Param("grantId") String grantId, @Param("generation") long generation,
                  @Param("endpoint") String endpoint, @Param("now") Instant now);

    @Select("SELECT activity_id, user_id, grant_id, member_generation, endpoint FROM network_peer_endpoints WHERE activity_id = #{activityId} ORDER BY user_id")
    List<Endpoint> list(String activityId);

    record Endpoint(String activityId, String userId, String grantId, Long memberGeneration, String endpoint) {
    }
}
