package xiaowu.backed.domain.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import xiaowu.backed.domain.recommendation.entity.RealtimeRecommendation;

/**
 * 实时推荐结果仓储
 */
public interface RealtimeRecommendationRepository
        extends JpaRepository<RealtimeRecommendation, Long> {

    /**
     * 查询用户最新的实时推荐列表（按 rank 升序）
     */
    List<RealtimeRecommendation> findByUserIdOrderByRankPosAsc(Long userId);

    /**
     * 删除用户旧的推荐记录（写入新一批前清理）
     */
    @Modifying
    @Query("DELETE FROM RealtimeRecommendation r WHERE r.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
