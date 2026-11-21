package com.tianji.aigc.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import com.tianji.config.SystemPromptConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceeImpl implements ChatService {

    private final ChatClient chatClient;
    private final SystemPromptConfig systemPromptConfig;
    private final RedissonClient redissonClient;
    private final ChatMemory chatMemory;
    /**
     * 向量存储（RAG 增强用）。
     * 未引入 VectorStore 实现（如 Redis Vector Store + Jedis）时为 null，
     * 此时跳过 RAG Advisor，聊天功能不受影响。
     */
    private final VectorStore vectorStore;
    /**
     * 是否启用 RAG 增强（向量存储可用时为 true）
     */
    private final boolean ragEnabled;
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

    // 生成请求id
    String requestId = IdUtil.fastSimpleUUID();
    // 获取用户id
    Long userId = UserContext.getUser();

    // 输出结束的标记
    private static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();

    // 创建RAG增强
    Advisor qaAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
            .searchRequest(SearchRequest.builder()
                    .similarityThreshold(0.6d)
                    .topK(6)
                    .build())
            .build();

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 获取对话id
        var conversationId = ChatService.getConversationId(sessionId);
        // 大模型输出内容的缓存器，用于在输出中断后的数据存储
        var outputBuilder = new StringBuilder();
        // 保证中断场景下（取消 / 异常）助手消息只补写一次
        var historySaved = new AtomicBoolean(false);

        return this.chatClient.prompt()
                .system(promptSystem -> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get()) // 设置系统提示语
                        .param("now", DateUtil.now()) // 设置当前时间的参数
                )
                .advisors(advisor -> advisor
                        // 设置RAG增强
                        .advisors(qaAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of(Constant.REQUEST_ID, requestId, Constant.USER_ID, userId)) //通过工具上下文传递参数
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
                    // 异常中断同样不会触发 ChatMemory.add，需要补写已输出的内容
                    this.saveStopHistoryRecord(conversationId, outputBuilder, historySaved);
                })
                .doOnCancel(() -> {
                    // 当输出被取消时，保存输出的内容到历史记录中
                    // 注意：doOnCancel 必须位于 takeWhile 之上，否则回调不生效
                    this.saveStopHistoryRecord(conversationId, outputBuilder, historySaved);
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
                    // 对于响应结果进行处理，如果是最后一条数据，就把此次消息id放到内存中
                    // 主要用于存储消息数据到 redis中，可以根据消息di获取的请求id，再通过请求id就可以获取到参数列表了
                    // 从而解决，在历史聊天记录中没有外参数的问题
                    var finishReason = chatResponse.getResult().getMetadata().getFinishReason();
                    if (StrUtil.equals(Constant.STOP, finishReason)) {
                        var messageId = chatResponse.getMetadata().getId();
                        ToolResultHolder.put(messageId, Constant.REQUEST_ID, requestId);
                    }
                    // 获取大模型的输出的内容
                    String text = chatResponse.getResult().getOutput().getText();
                    // 追加到输出内容中
                    outputBuilder.append(text);
                    // 封装响应对象
                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(Flux.defer(() -> {
                    // 通过请求id获取到参数列表，如果不为空，就将其追加到返回结果中
                    var map = ToolResultHolder.get(requestId);
                    if (CollUtil.isNotEmpty(map)) {
                        ToolResultHolder.remove(requestId); // 清除参数列表

                        // 响应给前端的参数数据
                        ChatEventVO chatEventVO = ChatEventVO.builder()
                                .eventData(map)
                                .eventType(ChatEventTypeEnum.PARAM.getValue())
                                .build();
                        return Flux.just(chatEventVO, STOP_EVENT);
                    }
                    return Flux.just(STOP_EVENT);
                }));
    }

    /**
     * 补写中断场景下的助手消息（停止生成 / 异常中断）。
     * <p>
     * Flux 被 cancel 或异常中断时，Spring AI 不会触发 ChatMemory.add()，
     * 需要手动把已输出的内容写入会话历史，否则下一轮对话会丢失这段回答。
     *
     * @param conversationId 会话id
     * @param outputBuilder  已累积的输出内容
     * @param historySaved   保证只补写一次，避免 cancel / error 重复写入
     */
    private void saveStopHistoryRecord(String conversationId, StringBuilder outputBuilder,
                                       AtomicBoolean historySaved) {
        String content = outputBuilder.toString();
        // 用户刚发起就立刻停止时，还没有任何输出，不写入空的助手消息
        if (content.isBlank()) {
            return;
        }
        if (historySaved.compareAndSet(false, true)) {
            this.chatMemory.add(conversationId, new AssistantMessage(content));
        }
    }

    @Override
    public void stop(String sessionId) {
        // 从 Redis 中移除生成状态标记
        // takeWhile() 会在下一次检查时读取不到 true，从而终止 Flux 流
        getStatusBucket(sessionId).delete();
        log.info("会话 {} 的生成已被用户停止", sessionId);
    }
}
