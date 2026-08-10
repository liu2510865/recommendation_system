package xiaowu.backed.interfaces.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.service.ChatService;

/**
 * AI 对话控制器 —— 基于用户画像的个性化聊天入口
 *
 * <p>
 * 使用方式（curl）：
 *
 * <pre>
 *   curl -X POST "http://localhost:8922/api/chat?userId=1001" \
 *        -H "Content-Type: text/plain" \
 *        -d "帮我推荐一款手机"
 * </pre>
 *
 * @author xiaowu
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    /**
     * 发送消息给 AI，获取基于画像的个性化回复
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @RequestParam Long userId,
            @RequestBody String message) {

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponse(false, "消息不能为空", null));
        }

        try {
            var reply = chatService.chat(userId, message);
            return ResponseEntity.ok(new ChatResponse(true, "success", reply));
        } catch (Exception e) {
            log.error("[Chat] AI call failed: userId={}", userId, e);
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse(false, "AI 服务暂时不可用", null));
        }
    }

    private record ChatResponse(boolean success, String message, String reply) {
    }
}
