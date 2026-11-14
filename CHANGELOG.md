# 更新记录

## 2026-11-10

> 代码编写于 2026-11-09，本次推送于 2026-11-10。

**本次推送内容：实现 SSE 流式对话与会话管理接口；停用原 Nacos 动态提示词配置**

### 新增（14 个文件）

**AI 对话（SSE 流式）**
- `aigc/controller/ChatController.java` — `POST /chat`，`text/event-stream` 流式返回
- `aigc/service/ChatService.java`、`aigc/service/impl/ChatServiceImpl.java` — `ChatClient.prompt().stream()` 逐段封装为事件，末尾追加 STOP
- `aigc/dto/ChatDTO.java` — 请求体 `question` / `sessionId`
- `aigc/vo/ChatEventVO.java` — 流式事件体 `eventData` / `eventType`
- `aigc/enums/ChatEventTypeEnum.java` — DATA(1001) / STOP(1002) / PARAM(1003)

**会话管理**
- `aigc/controller/SessionController.java` — `POST /session` 新建会话、`GET /session/hot` 热门示例
- `aigc/service/ChatSessionService.java`、`aigc/service/impl/ChatSessionServiceImpl.java` — UUID 生成会话 ID、随机抽示例、持久化
- `aigc/entity/ChatSession.java` — `chat_session` 表实体
- `aigc/mapper/ChatSessionMapper.java` — MyBatis-Plus `BaseMapper`
- `aigc/vo/SessionVO.java` — 会话响应体（含内嵌 `Example`）
- `aigc/config/SessionProperties.java` — 绑定 `tj.ai.session`（title / describe / examples）

**基础配置**
- `config/SpringAIConfig.java` — 装配 `ChatClient` Bean，挂 `SimpleLoggerAdvisor`

### 修改（3 个文件）
- `config/AIProperties.java`、`config/SystemPromptConfig.java` — **整体注释停用**（保留原 Nacos 动态系统提示词设计代码，暂不启用）
- `resources/application-local.yml` — 新增 `nacos:aigc-service.yaml` 配置导入

### 文档
- `README.md` — 按当前实现重写功能说明与目录结构，标注动态提示词机制已停用
- `CHANGELOG.md` — 本文件，记录更新日期与推送内容

### 安全核查
无硬编码密钥；仓库内仅保留教学环境的 Nacos 默认口令 `nacos/nacos` 与内网地址示例值。

---

## 2026-11-11

> 代码编写于 2026-11-10，本次推送于 2026-11-11。本地已有 3 个提交：`69d7f75`、`b5534d8`、`ca1111a`。

**本次推送内容：接入 Nacos 系统提示词；实现大模型"停止生成"接口，并从本地 ConcurrentHashMap 优化为 Redis（Redisson）方案。**

### 1、在 Nacos 中配置提示词

- **重新启用** `config/SystemPromptConfig.java` 与 `config/AIProperties.java`（11-10 曾被整体注释停用），恢复从 Nacos 拉取系统提示词 + `Listener` 热更新 + `AtomicReference` 缓存的机制。
- `resources/application.yml` 新增配置：
  - `tj.ai.prompt.system.chat`：`data-id: system-chat-message.txt`、`group: DEFAULT_GROUP`、`timeout-ms: 20000`
  - `spring.ai.dashscope.api-key` 等 DashScope 基础项
- `resources/application-local.yml`：Nacos config 指定 `namespace: ""`；本服务配置导入改为 `optional:nacos:${spring.application.name}.yaml`（配置不存在也允许启动）；移除上一版新增的 `nacos:aigc-service.yaml`。
- `aigc/service/impl/ChatServiceImpl.java`：注入 `SystemPromptConfig`，在 `ChatClient.prompt()` 中通过 `.system(...)` 设置系统提示词，并用 `.param("now", DateUtil.now())` 注入当前时间参数，支持提示词模板占位符。

> 提示词正文放在 Nacos 的 `system-chat-message.txt` 配置里，**不进代码仓库**，改提示词无需重启服务。

### 2、使用 ConcurrentHashMap 完成大模型停止的接口开发（第一版）

- `aigc/controller/ChatController.java` 新增 `POST /chat/stop?sessionId=xxx`；`aigc/service/ChatService.java` 新增 `stop(String sessionId)`。
- `ChatServiceImpl` 用静态 `Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>()` 记录各会话生成状态：
  - `doFirst` 首段输出前置 `true`；`doOnError` / `doOnComplete` 清除标记
  - `takeWhile` 每读一段就检查标记，`false` 时终止 `Flux` 流
  - `stop()` 直接 `remove(sessionId)` 让流在下一次检查时停下
- 用 `ConcurrentHashMap` 保证并发读写线程安全；代码注释里已注明局限：**仅单机内存有效，分布式环境需换 Redis**。

### 3、优化：使用 Redis 完成大模型停止的接口开发（当前版本）

- `pom.xml` 新增 `org.redisson:redisson` 依赖。
- `ChatServiceImpl` 去掉 `ConcurrentHashMap`，改用 `RedissonClient.getRBucket<Boolean>`：
  - key 格式 `aigc:generate:status:{sessionId}`，**设置 10 分钟过期**，防止会话异常结束时 key 泄漏
  - `doFirst` 写入 `true`；`doOnError` / `doOnComplete` 删除；`takeWhile` 从 Redis 读状态决定是否继续
  - `stop()` 删除该 key，`takeWhile` 下一次检查读不到 `true` 即终止流
- **价值**：停止信号存在共享 Redis 中，`stop()` 可由集群任意节点调用并广播，解决第 2 版单机内存方案在分布式部署下失效的问题。

### 其他改动

- `src/test/java/` 下新增 `test.java`、`algo/Sorts.java`、`algo/SortsTest.java` —— **算法练习沙箱**（直接跑 `main()` / 单元测试，不启动 Spring Boot），与 AIGC 服务无关，仅作为练习代码一并收录。
- `README.md` 同步更新：动态提示词由"已停用"改回"已启用"，补充停止生成接口与 Redis 方案说明。
- `CHANGELOG.md` 追加本节。

### 安全核查

- 无真实硬编码密钥；仅保留教学环境 `nacos/nacos` 默认口令与内网地址。
- ⚠️ `application.yml` 中 `spring.ai.dashscope.api-key` 为占位字符串 `sk-placeholder-for-local-dev`（**非真实密钥**），本地开发请替换为环境变量或 Nacos 配置，切勿把真实 API Key 提交进仓库。

---

## 2026-11-14

> 代码编写于 2026-11-13 ~ 11-14，本次推送于 2026-11-14。本地已有提交：`8b3fc3d`（4、完成了会话记忆功能）。

**本次推送内容：完成多轮会话记忆功能（基于 Redis 持久化的 Spring AI ChatMemory），并补充历史消息查询接口。**

### 新增 / 修改（13 个文件，+355 行）

**`aigc/memory/` 新包 —— 自定义 Redis 记忆存储**
- `RedisChatMemoryRepository.java` — 实现 Spring AI `ChatMemoryRepository` SPI，用 Redis List 存会话消息（key `CHAT:{conversationId}`），实现 `findConversationIds` / `findByConversationId` / `saveAll` / `deleteByConversationId`
- `MyMessage.java` — 序列化中间 DTO（扁平承载 messageType / textContent / metadata / media / toolCalls / toolResponses / params）
- `MessageUtil.java` — `Message ⇄ JSON` 双向转换，`toMessage()` 按 `MessageType` 分派还原四类具体消息
- `package-info.java` — 包级 `@NonNullApi` / `@NonNullFields`

**配置与装配**
- `aigc/config/SpringAIConfig.java`（新增）— 装配 `ChatMemoryRepository` → `MessageWindowChatMemory`（`maxMessages` 由 `tj.ai.memory.max` 注入）→ `MessageChatMemoryAdvisor`，并把记忆 Advisor 挂进 `ChatClient.defaultAdvisors`
- `resources/application.yml` — 新增 `tj.ai.memory.max: 100`
- `aigc/service/ChatService.java` — 新增静态方法 `getConversationId(sessionId)`，规则 `userId_sessionId`
- `aigc/service/impl/ChatServiceImpl.java` — 对话时 `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))` 运行时传入对话 ID

**历史消息查询**
- `aigc/controller/SessionController.java` — 新增 `GET /session/{sessionId}`
- `aigc/service/ChatSessionService.java` / `impl/ChatSessionServiceImpl.java` — `chatMemory.get(conversationId)` 后过滤只保留 USER / ASSISTANT，转 `MessageVO`
- `aigc/vo/MessageVO.java`、`aigc/enums/MessageTypeEnum.java` — 历史消息响应体与业务枚举（USER=1 / ASSISTANT=2）

### 文档
- `README.md` — **新增「会话记忆（重点）」大章节**：Spring AI ChatMemory 三层抽象表、装配链路、Redis 存储设计、**7 个难点重点**（Message 接口多态序列化、`saveAll` 全量覆盖语义、对话 ID 隔离、窗口策略、历史查询类型过滤、与停止生成共用 Redis、包级非空约束）、已知问题与改进方向、消息存储格式示例；同步更新接口表、技术栈、目录结构、配置说明
- `CHANGELOG.md` — 追加本节

### ⚠️ 本次发现的代码隐患
- 曾存在**两个 `SpringAIConfig`**：`com.tianji.config`（旧版，仅 `loggerAdvisor`）与 `com.tianji.aigc.config`（新版，含记忆 Advisor），都是 `@Configuration` 且都定义 `chatClient` / `loggerAdvisor` Bean → 可能触发 `BeanDefinitionOverrideException`，或装配到不带记忆 Advisor 的 `ChatClient` 导致记忆失效。**当日已修复，见下方补充推送**。
- 其他改进点（`KEYS` 命令阻塞风险、`saveAll` 非原子、Redis List 无 TTL、按条数而非 Token 裁剪）仍待优化 —— 详见 README「已知问题与改进方向」。

### 安全核查
无新增硬编码密钥；沿用教学环境 `nacos/nacos` 默认口令、`sk-placeholder-for-local-dev` 占位符与内网地址示例值。

---

## 2026-11-14（补充推送）：删除重复配置类，修复 Bean 冲突

- **删除** `src/main/java/com/tianji/config/SpringAIConfig.java`（旧版，只装配 `ChatClient` + `SimpleLoggerAdvisor`，不含记忆 Advisor）。
- **保留** `src/main/java/com/tianji/aigc/config/SpringAIConfig.java` 作为 `ChatClient` 与 `MessageChatMemoryAdvisor` 的**唯一装配点**，消除 Bean 定义冲突，确保会话记忆链路一定生效。
- `com.tianji.config` 包仍保留 `AIProperties.java` 与 `SystemPromptConfig.java`（Nacos 提示词配置），不受影响。
- 文档同步：`README.md` 把「已知问题」里的冲突项标记为已修复、目录结构去掉旧版条目；`CHANGELOG.md` 追加本节。
- 安全核查：纯删除操作，无新增内容与密钥。
