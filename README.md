# tj-aigc · AIGC 服务

`天机学堂` 项目中的 AI 能力微服务，基于 **Spring Boot + Spring AI Alibaba（DashScope 通义千问）**，提供 **SSE 流式对话**、**停止生成**、**会话管理** 与 **多轮会话记忆（Redis 持久化）** 能力，系统提示词由 **Nacos 配置中心动态下发**。

> 仓库于 2026-11-08 首次提交（基础骨架），此后逐日迭代。完整变更见 [CHANGELOG.md](./CHANGELOG.md)。

## 技术栈

| 组件 | 版本 / 说明 |
|------|-------------|
| JDK | 17 |
| Spring Boot | 3.x（由父工程 `com.tianji:tjxt:1.0.0` 统一管理） |
| Spring AI | BOM 由父工程 `${spring-ai.version}` 指定 |
| Spring AI Alibaba | `1.0.0.2`（`spring-ai-alibaba-starter-dashscope`） |
| 会话记忆 | Spring AI `ChatMemory` / `MessageWindowChatMemory` / `MessageChatMemoryAdvisor` |
| 注册 / 配置中心 | Spring Cloud Alibaba Nacos（discovery + config） |
| 存储 | Redis（`StringRedisTemplate` + **Redisson**）、MySQL + MyBatis-Plus |
| 工具库 | Hutool（`BeanUtil` / `JSONUtil` / `CollStreamUtil` / `IdUtil` / `RandomUtil`） |
| 接口文档 | Knife4j（OpenAPI 3） |
| 流式输出 | Project Reactor `Flux` + SSE（`text/event-stream`） |
| 服务端口 | `8094`，服务名 `aigc-service`，数据库 `tj_aigc` |

## 已实现功能

| 接口 | 方法 | 说明 |
|------|------|------|
| `/chat` | POST | 流式对话，请求体 `ChatDTO { question, sessionId }`，返回 `Flux<ChatEventVO>` |
| `/chat/stop` | POST | 停止指定会话的生成，参数 `sessionId` |
| `/session` | POST | 新建会话（`?n=3` 控制示例条数） |
| `/session/hot` | GET | 随机返回热门示例问题 |
| `/session/{sessionId}` | GET | 查询该会话的历史消息列表 |

## 会话记忆（重点）

### 功能说明

让大模型"记得住"同一会话里之前说过什么：第 1 轮问"北京天气"，第 2 轮问"那上海呢"，模型要能理解这是在问天气。历史消息**持久化在 Redis 而非进程内存**，因此服务重启、多实例部署都不会丢上下文；同时提供历史消息查询接口，前端可回显聊天记录。

### 使用的技术：Spring AI ChatMemory 三层抽象

Spring AI 把"会话记忆"拆成三个可替换的角色，本项目逐层落地：

| 层次 | Spring AI 抽象 | 本项目实现 | 职责 |
|------|----------------|-----------|------|
| 存储层 | `ChatMemoryRepository` | `RedisChatMemoryRepository` | 定义消息怎么存、怎么取（SPI 接口） |
| 策略层 | `ChatMemory` | `MessageWindowChatMemory` | 决定保留多少条、如何淘汰 |
| 拦截层 | `Advisor` | `MessageChatMemoryAdvisor` | 请求前把历史注入 prompt，响应后把本轮写回 |

装配在 `SpringAIConfig`（`com.tianji.aigc.config` 包）：

```java
@Bean
public ChatMemoryRepository redisChatMemoryRepository() {
    return new RedisChatMemoryRepository();
}

@Bean
public ChatMemory chatMemory(ChatMemoryRepository repo) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repo)
            .maxMessages(maxMessages)   // tj.ai.memory.max，默认 100
            .build();                    // 超出窗口自动淘汰最旧消息
}

@Bean
public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
    return MessageChatMemoryAdvisor.builder(chatMemory).build();
}

@Bean
public ChatClient chatClient(ChatClient.Builder builder,
                             Advisor loggerAdvisor,
                             Advisor messageChatMemoryAdvisor) {
    return builder.defaultAdvisors(loggerAdvisor, messageChatMemoryAdvisor).build();
}
```

对话发起时，`ChatServiceImpl` 通过 `.advisors(...)` 在**运行时**传入对话 ID，而不是写死在默认 Advisor 里：

```java
String conversationId = ChatService.getConversationId(sessionId); // {userId}_{sessionId}
chatClient.prompt()
        .system(...)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .user(question)
        .stream().chatResponse();
```

`MessageChatMemoryAdvisor` 会据此取出该 ID 的历史消息拼进本次 prompt，并在流式响应结束后把本轮的 USER / ASSISTANT 消息写回 Repository。业务代码**不手工管理历史**，只负责传一个 ID。

### 自定义 Redis 存储实现

`RedisChatMemoryRepository implements ChatMemoryRepository`，用 **Redis List** 存一个会话的消息：

- key：`CHAT:{conversationId}`（前缀可构造参数覆盖）
- 每条消息是一条 JSON 字符串，`rightPush` 追加，保持时序
- `findByConversationId`：`boundListOps(key).range(0, -1)` 取全量再反序列化
- `findConversationIds`：`keys(prefix + "*")` 枚举所有会话

`ChatMemoryRepository` 接口的四个方法都要实现：`findConversationIds` / `findByConversationId` / `saveAll` / `deleteByConversationId`。

### 难点与重点

#### 1. Spring AI 的 `Message` 不能直接 JSON 序列化（最核心）

`Message` 是**接口**，实现类有 `UserMessage`、`AssistantMessage`、`SystemMessage`、`ToolResponseMessage`，字段结构各不相同，且多为不可变对象、通过 Builder/构造器创建——直接 `JSONUtil.toJsonStr(message)` 再反序列化会丢字段、找不到构造器、无法确定该还原成哪个子类。

本项目引入**中间 DTO + 双向转换器**解决：

- `MyMessage`：扁平结构，统一承载 `messageType`、`textContent`、`metadata`、`media`、`toolCalls`、`toolResponses`、`params`
- `MessageUtil.toJson(Message)`：用 `BeanUtil.toBean` 拷成 DTO，再显式 `setTextContent(message.getText())`；对 `AssistantMessage` 补 `getToolCalls()`、对 `ToolResponseMessage` 补 `getResponses()`
- `MessageUtil.toMessage(String)`：先反序列化成 `MyMessage`，读 `messageType` 走 `switch` 分派，**分别 new 出对应的具体实现类**（`SystemMessage` / `UserMessage.builder()` / `AssistantMessage` / `ToolResponseMessage`），无法识别则抛异常

> 本质是**用"类型标签 + 扁平字段"绕开接口多态的反序列化难题**，这是自定义 `ChatMemoryRepository` 时最容易踩的坑。

#### 2. `saveAll` 是全量覆盖语义，不是增量追加

Spring AI 调用 `saveAll(conversationId, messages)` 时传入的是**窗口内的完整消息列表（含历史）**，不是本轮新增的两条。所以实现里必须**先 `deleteByConversationId` 清空，再逐条 `rightPush`**；如果直接追加，历史会重复累加、越聊越长，最终撑爆上下文。

```java
public void saveAll(String conversationId, List<Message> messages) {
    Assert.notEmpty(messages, "消息列表不能为空");
    var listOps = stringRedisTemplate.boundListOps(getKey(conversationId));
    this.deleteByConversationId(conversationId);   // 关键：先清
    messages.forEach(m -> listOps.rightPush(MessageUtil.toJson(m)));
}
```

#### 3. 对话 ID 的隔离设计

`ChatService.getConversationId(sessionId)` 返回 `UserContext.getUser() + "_" + sessionId`：

- **用户维度隔离**：不同用户的历史互不可见，避免越权串数据
- **会话维度隔离**：同一用户开多个会话，靠 UUID `sessionId` 区分
- 查询历史时复用同一规则，保证读写落到同一个 Redis key

#### 4. 窗口策略与 Token 控制

`MessageWindowChatMemory` 的 `maxMessages = tj.ai.memory.max`（默认 **100**）按**消息条数**裁剪，超出的最旧消息自动淘汰，防止 prompt 无限膨胀。注意这是"条数窗口"而非"Token 窗口"——单条超长文本仍可能撑大上下文。

#### 5. 历史查询要过滤消息类型

`GET /session/{sessionId}` 调 `chatMemory.get(conversationId)` 后，**只保留 USER / ASSISTANT**：

- SYSTEM（系统提示词）、TOOL（工具调用结果）属于内部消息，不该回显给前端
- `MessageTypeEnum`（`USER=1` / `ASSISTANT=2`）是**业务侧 VO 枚举**，与 Spring AI 内部的 `MessageType`（`SYSTEM/USER/ASSISTANT/TOOL`）是两套，转换时用 `valueOf(message.getMessageType().name())`，别混用

#### 6. 与"停止生成"共用 Redis 但互不干扰

两套 key 前缀各司其职：记忆用 `CHAT:{conversationId}`，停止状态用 `aigc:generate:status:{sessionId}`（Redisson `RBucket`，10 分钟过期）。Advisor 写回历史发生在流式响应结束后，而 `takeWhile` 终止流时仍会触发 `doOnComplete` 清理停止状态，两者生命周期独立。

#### 7. 包级非空约束

`memory/package-info.java` 用 `@NonNullApi` / `@NonNullFields` 声明整个包默认非空，配合 IDE / 静态分析减少 `Message` 转换处的空指针。

### 已知问题与改进方向（如实记录）

| 问题 | 影响 | 建议 |
|------|------|------|
| ⚠️ **两个 `SpringAIConfig` 并存**：`com.tianji.config` 与 `com.tianji.aigc.config` 各有一个，都是 `@Configuration`，且都定义 `chatClient`、`loggerAdvisor` Bean | Spring Boot 默认禁止 Bean 覆盖，启动可能报 `BeanDefinitionOverrideException`；即便允许覆盖，也可能装配到不带记忆 Advisor 的那个 `ChatClient`，导致记忆失效 | **删除旧的 `com.tianji.config.SpringAIConfig`**，只保留 `aigc/config` 版本 |
| `findConversationIds()` 用 `KEYS prefix*` | Redis 单线程，`KEYS` 在数据量大时会阻塞整个实例 | 改 `SCAN` 游标遍历，或维护会话 ID 集合 |
| `saveAll` = 先删后逐条 `rightPush` | 非原子；中途异常会留下半截历史 | 用 `pipeline` 批量写，或改为单个 String 存整个 JSON 数组一次写入 |
| Redis List 无 TTL | 历史消息永久占用内存 | 按业务设过期时间，或定期归档到 MySQL |
| 按条数窗口裁剪 | 长文本单条就可能撑爆上下文 | 改按 Token 计数裁剪，或做摘要压缩 |

### 消息存储格式示例

`CHAT:{userId}_{sessionId}` 是一个 Redis List，元素形如：

```json
{"messageType":"USER","textContent":"北京今天天气怎么样？","metadata":{},"media":[],"toolCalls":[],"toolResponses":[],"params":{}}
{"messageType":"ASSISTANT","textContent":"北京今天晴，气温 18℃…","metadata":{},"media":[],"toolCalls":[],"toolResponses":[],"params":{}}
```

`messageType` 就是反序列化时 `MessageType.valueOf(...)` 分派还原的依据。

## SSE 流式对话

- `ChatServiceImpl` 调用 `ChatClient.prompt().system(...).user(question).stream().chatResponse()`
- 系统提示词取自 `SystemPromptConfig`（Nacos 动态加载），并通过 `.param("now", DateUtil.now())` 注入当前时间参数，支持提示词模板占位符
- 每段模型输出封装为 `ChatEventVO(eventData=文本, eventType=DATA)`，末尾 `concatWith` 追加 `STOP` 事件
- `ChatEventTypeEnum` 定义 `DATA(1001)` / `STOP(1002)` / `PARAM(1003)`
- Controller 用 `@NoWrapper` 跳过统一响应包装，直接输出事件流

### 停止生成（Redis / Redisson 方案）

**用一个共享状态位控制 `Flux` 流的中止**：

1. `doFirst` 首段输出前，在 Redis 写入 `aigc:generate:status:{sessionId} = true`，**设置 10 分钟过期**（防止会话异常结束时 key 泄漏）
2. `takeWhile` 每读一段就从 Redis 读该状态，`true` 继续、`false`/不存在则终止流
3. `POST /chat/stop` 调用 `stop(sessionId)` 删除该 key → 下一次 `takeWhile` 检查时流自然终止
4. `doOnError` / `doOnComplete` 负责清理状态

**为什么用 Redis 而不是本地 Map**：初版用 `ConcurrentHashMap` 存状态，仅单机内存有效；分布式部署时，生成请求和停止请求可能落到不同节点，本地 Map 无法跨节点生效。改用 Redisson `RBucket` 后，停止信号存在共享 Redis 中，任意节点调用 `stop()` 都能广播终止。

### 会话管理

- `POST /session` — `IdUtil.fastSimpleUUID()` 生成 `sessionId`，随机抽 n 个示例问题，并把会话（含 `UserContext` 当前用户）持久化到 `chat_session` 表（MySQL）
- `GET /session/hot` — 随机返回 n 个热门示例
- `GET /session/{sessionId}` — 从 Redis 记忆中读取并回显历史消息
- `SessionProperties` 绑定 `tj.ai.session`（`title` / `describe` / `examples`），示例数据来自 Nacos 配置

> 注意这里存在**双存储**：会话元数据在 MySQL `chat_session` 表，会话消息在 Redis。两者靠 `sessionId` + 用户 ID 关联。

## 系统提示词：Nacos 动态下发

提示词正文**不写死在代码里，也不进仓库**，放在 Nacos 配置文件 `system-chat-message.txt` 中，改提示词无需重启服务：

1. `AIProperties` 绑定 `tj.ai.prompt.system.chat`（`data-id` / `group` / `timeout-ms`）
2. `SystemPromptConfig` 在 `@PostConstruct` 阶段通过 `NacosConfigManager.getConfigService().getConfig(...)` 拉取
3. `AtomicReference<String>` 保存，保证并发读写安全
4. 注册 Nacos `Listener`，配置变更回调 `receiveConfigInfo` 直接替换内存中的提示词
5. `ChatServiceImpl` 通过 `.system(...)` 注入 `ChatClient`

> 该机制在 2026-11-10 曾被整体注释停用，2026-11-11 重新启用并接入对话链路。

## 目录结构

```
tj-aigc/
├── pom.xml
├── README.md
├── CHANGELOG.md
└── src/
    ├── main/
    │   ├── java/com/tianji/
    │   │   ├── AIGCApplication.java                    # 启动类（@EnableAsync）
    │   │   ├── config/
    │   │   │   ├── SpringAIConfig.java                # ⚠️ 旧版（仅 loggerAdvisor），建议删除
    │   │   │   ├── AIProperties.java                  # 绑定 tj.ai.prompt.*
    │   │   │   └── SystemPromptConfig.java            # Nacos 拉取提示词 + 热更新
    │   │   └── aigc/
    │   │       ├── config/SessionProperties.java      # tj.ai.session 配置
    │   │       ├── config/SpringAIConfig.java        # ✅ 当前版：ChatClient + 记忆 Advisor 装配
    │   │       ├── memory/                           # 会话记忆实现
    │   │       │   ├── RedisChatMemoryRepository.java  # ChatMemoryRepository 的 Redis 实现
    │   │       │   ├── MyMessage.java                  # 序列化中间 DTO
    │   │       │   ├── MessageUtil.java                # Message ⇄ JSON 双向转换
    │   │       │   └── package-info.java               # 包级 @NonNullApi
    │   │       ├── controller/ChatController.java    # POST /chat、POST /chat/stop
    │   │       ├── controller/SessionController.java # /session 系列接口
    │   │       ├── dto/ChatDTO.java
    │   │       ├── entity/ChatSession.java           # chat_session 表
    │   │       ├── enums/ChatEventTypeEnum.java
    │   │       ├── enums/MessageTypeEnum.java        # 前端 VO 消息类型（USER/ASSISTANT）
    │   │       ├── mapper/ChatSessionMapper.java
    │   │       ├── service/ChatService.java          # 含 getConversationId 静态方法
    │   │       ├── service/ChatSessionService.java
    │   │       ├── service/impl/ChatServiceImpl.java # 流式对话 + Redis 停止控制
    │   │       ├── service/impl/ChatSessionServiceImpl.java
    │   │       ├── vo/ChatEventVO.java
    │   │       ├── vo/MessageVO.java                 # 历史消息响应体
    │   │       └── vo/SessionVO.java
    │   └── resources/
    │       ├── application.yml                       # 主配置：端口、DashScope、提示词、memory.max
    │       ├── application-local.yml                 # 本地环境（默认激活）
    │       ├── application-dev.yml                   # 开发环境
    │       └── application-test.yml                  # 测试环境
    └── test/java/
        ├── test.java                                 # 算法练习沙箱（main 直接跑）
        └── algo/Sorts.java, SortsTest.java           # 排序算法练习与用例
```

> `src/test/java/` 下是**算法练习代码**（直接跑 `main()` / 单元测试，不启动 Spring Boot），与 AIGC 服务无关，仅作为练习记录一并收录。

## 运行前提（重要）

本模块**不能独立构建运行**，依赖同项目（本仓库未包含）的：

- 父工程 `com.tianji:tjxt:1.0.0`（统一依赖版本管理）
- 内部模块 `com.tianji:tj-api:1.0.0`、`com.tianji:tj-auth-resource-sdk:1.0.0`，以及 `tj-common` 中的 `UserContext`、`NoWrapper`、`BaseEnum` 等
- Nacos 共享配置：`shared-spring.yaml`、`shared-redis.yaml`、`shared-logs.yaml`、`shared-feign.yaml`、`shared-mybatis.yaml`，以及提示词配置 `system-chat-message.txt`（`DEFAULT_GROUP`）
- Redis（`StringRedisTemplate` 与 Redisson 连接信息来自 `shared-redis.yaml`），以及 `chat_session` 表

启动前请按 `application-{profile}.yml` 中的 `server-addr` 部署/调整 Nacos 地址。

```bash
# 在包含父工程与依赖模块的完整项目中执行
mvn -pl tj-aigc -am clean package
java -jar tj-aigc/target/tj-aigc.jar --spring.profiles.active=local
```

接口文档：`http://localhost:8094/doc.html`

## 配置说明

- `spring.profiles.active` 默认 `local`；`local` / `dev` / `test` 主要差异是 Nacos 地址与注册 IP
- `application-local.yml` 中本服务配置导入用 `optional:nacos:${spring.application.name}.yaml`（配置不存在也允许启动），config `namespace: ""`
- `tj.ai.prompt.system.chat.data-id` 指定 Nacos 提示词配置名（默认 `system-chat-message.txt`）
- **`tj.ai.memory.max`**：会话记忆窗口上限（默认 `100` 条），经 `@Value("${tj.ai.memory.max:100}")` 注入 `MessageWindowChatMemory`
- `tj.swagger.*` 控制 Knife4j 文档开关、扫描包、标题等
- `tj.jdbc.database: tj_aigc` 指定业务库名
- ⚠️ `application.yml` 中 `spring.ai.dashscope.api-key` 为**占位字符串** `sk-placeholder-for-local-dev`，非真实密钥。本地开发请通过环境变量或 Nacos 注入真实 Key，**切勿把真实 API Key 提交进仓库**；仓库内 Nacos 地址与 `nacos/nacos` 口令同为教学环境示例值

## 更新记录

见 [CHANGELOG.md](./CHANGELOG.md)。

## 来源

天机学堂（tjxt）课程项目中的 AIGC 服务模块，本仓库仅收录该模块自身代码，用于学习与记录。
