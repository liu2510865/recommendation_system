package xiaowu.backed.interfaces.rest;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import xiaowu.backed.application.dto.RecallItemDetailDTO;
import xiaowu.backed.application.service.UserRecallService;
import xiaowu.backed.domain.recommendation.entity.RealtimeRecommendation;
import xiaowu.backed.domain.recommendation.repository.RealtimeRecommendationRepository;

/**
 * 推荐召回控制器
 *
 * <p>/recall/{userId} — ALS 模型召回（离线训练产出）
 * <p>/realtime/{userId} — 实时推荐（Spark Streaming 实时计算产出）
 */
@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecallController {

    private final UserRecallService userRecallService;
    private final RealtimeRecommendationRepository realtimeRecRepository;

    /**
     * ALS 模型召回（离线训练产出，查询 user_cf_recall 表）
     */
    @GetMapping("/recall/{userId}")
    public List<RecallItemDetailDTO> recall(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        return userRecallService.recall(userId, limit);
    }

    /**
     * 实时推荐查询（Spark Streaming 实时计算产出，查询 realtime_recommendation 表）
     */
    @GetMapping("/realtime/{userId}")
    public List<RealtimeRecommendation> realtime(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        var all = realtimeRecRepository.findByUserIdOrderByRankPosAsc(userId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }
}
