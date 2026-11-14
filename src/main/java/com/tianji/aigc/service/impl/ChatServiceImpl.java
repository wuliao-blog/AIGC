package com.tianji.aigc.service.impl;

import cn.hutool.core.date.DateUtil;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.config.SystemPromptConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final SystemPromptConfig systemPromptConfig;
    private final RedissonClient redissonClient;

    /**
     * Redis key 前缀：大模型生成状态
     * 完整 key 形如：aigc:generate:status:{sessionId}
     */
    private static final String GENERATE_STATUS_KEY_PREFIX = "aigc:generate:status:";
    /**
     * 生成状态 key 的过期时间，防止 key 泄漏（会话异常结束未清理的场景）
     */
    private static final long GENERATE_STATUS_EXPIRE = 10;
    private static final TimeUnit GENERATE_STATUS_EXPIRE_UNIT = TimeUnit.MINUTES;

    /**
     * 获取某个会话的生成状态 bucket
     */
    private RBucket<Boolean> getStatusBucket(String sessionId) {
        return redissonClient.getBucket(GENERATE_STATUS_KEY_PREFIX + sessionId);
    }

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 获取对话id
        var conversationId = ChatService.getConversationId(sessionId);

        return this.chatClient.prompt()
                .system(promptSystem -> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get()) // 设置系统提示语
                        .param("now", DateUtil.now()) // 设置当前时间的参数
                )
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(question)
                .stream()
                .chatResponse()
                .doFirst(() -> {
                    // 第一次输出前，在 Redis 中标记该会话正在生成
                    getStatusBucket(sessionId).set(true, GENERATE_STATUS_EXPIRE, GENERATE_STATUS_EXPIRE_UNIT);
                })
                .doOnError(throwable -> {
                    // 出现异常时，删除 Redis 中的状态标记
                    getStatusBucket(sessionId).delete();
                })
                .doOnComplete(() -> {
                    // 正常完成时，删除 Redis 中的状态标记
                    getStatusBucket(sessionId).delete();
                })
                .takeWhile(response -> {
                    // 从 Redis 读取状态，true 继续 Flux 流，false 终止
                    // 分布式环境下，stop() 可以由任意节点调用，通过 Redis 广播终止信号
                    Boolean status = getStatusBucket(sessionId).get();
                    return Boolean.TRUE.equals(status);
                })
                .map(chatResponse -> {
                    // 获取大模型的输出的内容
                    String text = chatResponse.getResult().getOutput().getText();
                    // 封装响应对象
                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(Flux.just(ChatEventVO.builder()  // 标记输出结束
                        .eventType(ChatEventTypeEnum.STOP.getValue())
                        .build()));
    }

    @Override
    public void stop(String sessionId) {
        // 从 Redis 中移除生成状态标记
        // takeWhile() 会在下一次检查时读取不到 true，从而终止 Flux 流
        getStatusBucket(sessionId).delete();
        log.info("会话 {} 的生成已被用户停止", sessionId);
    }
}
