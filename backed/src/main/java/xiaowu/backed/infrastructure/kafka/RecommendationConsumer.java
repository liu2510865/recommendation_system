package xiaowu.backed.infrastructure.kafka;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.dto.RecommendedItemDTO;
import xiaowu.backed.application.dto.UserRecommendationDTO;
import xiaowu.backed.domain.recommendation.entity.RealtimeRecommendation;
import xiaowu.backed.domain.recommendation.repository.RealtimeRecommendationRepository;

/**
 * 推荐 Topic 消费者 —— 消费 Spark Structured Streaming 产出的推荐结果，
 * 写入 realtime_recommendation 表供在线召回服务查询。
 *
 * <p>消费组: recommendation-result-group
 * <p>Topic: ${kafka.topic.recommendations}（默认 recommendations）
 *
 * <p>消息格式（UserRecommendationDTO）:
 * <pre>
 * {
 *   "userId": 1,
 *   "generatedAt": "2026-03-13T10:00:05Z",
 *   "windowStart": "2026-03-13T10:00:00Z",
 *   "windowEnd": "2026-03-13T10:00:30Z",
 *   "items": [
 *     {"rank": 1, "itemId": 101, "score": 8.0},
 *     {"rank": 2, "itemId": 88, "score": 5.0}
 *   ]
 * }
 * </pre>
 *
 * @author xiaowu
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecommendationConsumer {

    private final RealtimeRecommendationRepository realtimeRecRepository;
    private final ObjectMapper objectMapper;

    /**
     * 消费推荐结果消息，手动提交 offset（enable-auto-commit=false）。
     * 处理成功后才 ack，处理失败不 ack 触发 Kafka 重试。
     */
    @KafkaListener(
            topics = "${kafka.topic.recommendations}",
            groupId = "recommendation-result-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            var dto = objectMapper.readValue(record.value(), UserRecommendationDTO.class);
            log.info("[RecConsumer] userId={} items={} generatedAt={}",
                    dto.userId(), dto.items() != null ? dto.items().size() : 0, dto.generatedAt());

            persistRecommendation(dto);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[RecConsumer] Failed to process record: partition={} offset={} value={}",
                    record.partition(), record.offset(), record.value(), e);
            // 不 ack，让 Kafka 重试；连续失败由消费组的 max.poll.interval 触发 rebalance
        }
    }

    /**
     * 将推荐结果写入 realtime_recommendation 表。
     * 先清理用户旧推荐，再批量写入新推荐，保证每用户只保留最新一批。
     */
    @org.springframework.transaction.annotation.Transactional
    public void persistRecommendation(UserRecommendationDTO dto) {
        if (dto.userId() == null || dto.items() == null || dto.items().isEmpty()) {
            log.warn("[RecConsumer] Empty recommendation, skip. userId={}", dto.userId());
            return;
        }

        // 清理旧推荐
        realtimeRecRepository.deleteByUserId(dto.userId());
        realtimeRecRepository.flush();

        // 写入新推荐
        List<RealtimeRecommendation> entities = new ArrayList<>();
        for (RecommendedItemDTO item : dto.items()) {
            entities.add(RealtimeRecommendation.builder()
                    .userId(dto.userId())
                    .itemId(item.itemId())
                    .rankPos(item.rank())
                    .score(item.score())
                    .windowStart(dto.windowStart())
                    .windowEnd(dto.windowEnd())
                    .generatedAt(dto.generatedAt() != null ? dto.generatedAt() : Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
        }
        realtimeRecRepository.saveAll(entities);
        log.info("[RecConsumer] Persisted {} recommendations for userId={}", entities.size(), dto.userId());
    }
}
