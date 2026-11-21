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

---

## 2026-11-14（第 5 项）：解决「停止生成导致会话记忆丢失」bug

> 依据语雀《（一）基本对话与课程咨询》第 10 章「解决bug」（10.1 bug重现 / 10.2 bug解决）分析总结。本地提交：`2eae70c` + 本次补充修复。

**bug 介绍**：停止生成、会话记忆单独测试都正常，组合起来出问题——点「停止」后，大模型已输出的内容不写入 Redis 记忆，下一轮对话丢失这段回答。复现方式：在 `RedisChatMemoryRepository#saveAll` 与 `ChatServiceImpl#chat` 打断点，正常流会调 `saveAll` 两次（USER + ASSISTANT），点停止后只调一次（仅 USER）。

**根因**：停止靠 `takeWhile` 返回 false **中断（cancel）Flux** 实现；Flux 被 cancel 后 Spring AI 的 `MessageChatMemoryAdvisor` 不触发 `ChatMemory.add()`，自然不调 `ChatMemoryRepository#saveAll()`。文档注明「不确定是 Spring AI 故意设计还是版本问题（1.0.0 当时为最新版）」。

**解决方案**：框架不记就自己记——`StringBuilder` 在 `map` 中累积每段流式文本；`doOnCancel` 在流中断时手动 `chatMemory.add(conversationId, new AssistantMessage(content))`；只补 ASSISTANT 一条（USER 已由框架首次 `saveAll` 写入）。
⚠️ **重点**：`doOnCancel` 必须放在 `takeWhile` **之上**，否则 cancel 信号传不到、回调不生效。

**本次推送代码**（`ChatServiceImpl.java`）
- `2eae70c`：注入 `ChatMemory`、新增 `outputBuilder`、`map` 中 append、`doOnCancel` → `saveStopHistoryRecord()` → `chatMemory.add(...)`
- 补充修复：
  - 空内容跳过——刚发起就立刻停止时 `outputBuilder` 为空，避免写入空 `AssistantMessage`
  - `doOnError` 异常中断同样补写已输出内容
  - `AtomicBoolean historySaved` 保证 cancel / error 场景下只补写一次
- `application.yml` 新增 `tj.ai.memory.type: redis`（预留记忆类型配置，当前代码尚未读取）

**文档**：按确认采用从简方案，`README.md` 不扩写章节，本节记录 bug 与解决方案。

## 2026-11-18

> 推送于 2026-11-18。为后续大模型应用开发的 Function Calling 工具调用准备数据源接口。

**本次推送内容：新增 tj-api 微服务的课程服务 Feign 客户端（与 tj-aigc 并列的另一个微服务）**

### 新增（4 个文件，路径 `src/main/java/com/tianji/api/client/course/`）
- `CourseClient.java` — 课程服务核心接口：按课程 ID 查完整信息/基础信息/索引数据、按老师查出题讲课数、按小节查媒资、按媒资查引用次数、批量查课程简单信息
- `CatalogueClient.java` — 按目录 ID 列表批量查询目录基础信息
- `CategoryClient.java` — 查询全部一级课程分类
- `SubjectClient.java` — 按学科 ID 列表查询学科信息

### 说明
- 四个接口均通过 `@FeignClient(value = "course-service")` 调用课程微服务，是大模型工具调用（Function Calling）对接课程数据的入口。
- 作为 tj-api 微服务代码，与现有 `com.tianji.aigc` 包并列存放，保留原始包路径 `com.tianji.api.client.course`，便于后续按相同结构继续补充其他 Feign 客户端。

### 安全核查
纯接口声明，无密钥、无本地绝对路径、无旧品牌字样。

---

## 2026-11-18（Function Calling 工具接入）

> 推送于 2026-11-18。本地 commit 的课程查询工具代码，与上一批 course Feign 客户端配套。

**本次推送内容：接入 Function Calling，大模型可自主调用课程查询工具，并把工具结果回填到前端**

### 新增（4 个文件）
- `aigc/tools/CourseTools.java` — `@Tool` 标注的 `queryCourseById` 工具，通过 `CourseClient.baseInfo()` 查询课程，并把结果按「requestId + 字段名」存入 `ToolResultHolder`
- `aigc/tools/result/CourseInfo.java` — 工具返回的课程 VO，用 `@JsonPropertyDescription` 描述字段供大模型理解；价格分转元并四舍五入
- `aigc/config/ToolResultHolder.java` — `ConcurrentHashMap` 工具结果保持器，按 requestId 隔离并发请求，支持 put/get/remove
- `aigc/constants/Constant.java` — 工具描述、参数描述、REQUEST_ID 上下文 key 常量

### 修改（2 个文件）
- `aigc/config/SpringAIConfig.java` — `ChatClient` 增加 `.defaultTools(courseTools)`，注册默认工具
- `aigc/service/impl/ChatServiceImpl.java` — 生成 requestId 并经 `.toolContext()` 传给工具；流结束后用 `.concatWith()` 读取工具结果，以 `PARAM` 事件回填前端，随后发送 STOP

### 工作原理
1. 大模型识别用户意图后自主调用 `queryCourseById`，工具把结构化课程结果存入 `ToolResultHolder`（key = 请求 id）。
2. SSE 流结束前，`ChatServiceImpl` 按同一个 requestId 取出结果，追加一个 `PARAM` 事件推送给前端，前端可据此渲染课程卡片等结构化 UI。
3. `ToolResultHolder.remove()` 及时清理，避免内存泄漏。

### 说明
- 工具结果通过 `ToolContext` 传递 requestId，实现「同一次请求内工具 → 服务」的数据串联，避免多用户并发串数据。
- 与上一批 `com.tianji.api.client.course` 四个 Feign 客户端配套使用；这些客户端保留在仓库中未改动。

### 安全核查
无密钥、无本地绝对路径、无旧品牌字样。

---

## 2026-11-18（交易服务 Feign 客户端）

> 推送于 2026-11-18。与上一批 course 客户端、Function Calling 工具同属 tj-api 微服务对外接口。

**本次推送内容：新增 trade-service（交易服务）Feign 客户端及降级工厂，扩展大模型可调用的工具体系**

### 新增（4 个文件，路径 `src/main/java/com/tianji/api/client/trade/`）
- `TradeClient.java` — 交易服务核心接口：统计课程报名人数、统计学生报名课程数、检查当前用户是否已报名、统计课程购买/退款状态、预下单（生成订单 id 与可用优惠券）
- `CartClient.java` — 购物车接口：添加课程到购物车
- `fallback/TradeClientFallback.java` — 交易服务降级工厂，异常时返回空 Map / false / 空 DTO，避免雪崩
- `fallback/CartClientFallback.java` — 购物车降级工厂，异常时记录日志并静默返回

### 说明
- 与 `course` 包并列，均指向 `trade-service`，通过 `@FeignClient` 的 `contextId` 区分 trade / cart。
- 带 `fallbackFactory` 降级：微服务调用失败时返回安全默认值，是大模型工具调用链路的容错基础。
- 后续可作为 Function Calling 工具（如「查询课程报名人数」「检查我是否已报名」）的数据源。

### 安全核查
纯接口声明与降级实现，无密钥、无本地绝对路径、无旧品牌字样。

---

## 2026-11-18（预下单工具接入）

> 推送于 2026-11-18。用户本地完成"预下单（预下载）"功能后提交。

**本次推送内容：新增订单预下单 Function Calling 工具，扩展工具上下文以透传用户身份**

### 新增（2 个文件）
- `aigc/tools/OrderTools.java` — `@Tool` 标注的 `prePlaceOrder` 工具（描述"购买课程预下单操作"），调用 `TradeClient.prePlaceOrder()` 完成预下单；从 `ToolContext` 取 userId 通过 `UserContext.setUser()` 注入身份，避免 Feign 调用 401；结果存入 `ToolResultHolder`
- `aigc/tools/result/PrePlaceOrder.java` — 预下单结果 VO，``@JsonPropertyDescription` 描述订单字段供大模型理解；金额分转元保留两位，优惠券名按单券/叠加券格式化，计算实付金额

### 修改（6 个文件）
- `aigc/config/SpringAIConfig.java` — ChatClient 注入并注册 `OrderTools`（`.defaultTools(courseTools, orderTools)`）
- `aigc/constants/Constant.java` — 新增 `USER_ID` 上下文 key、`PRE_PLACE_ORDER` 工具描述、`COURSE_IDS` 参数描述
- `aigc/service/impl/ChatServiceImpl.java` — 从 `UserContext` 取 userId，`.toolContext()` 同时透传 requestId 与 userId（供工具注入身份）；保留 PARAM 事件回填逻辑
- `aigc/config/ToolResultHolder.java`、`aigc/tools/CourseTools.java`、`aigc/tools/result/CourseInfo.java` — 随工具类调整（保持结果与 requestId 映射机制）

### 工作原理
1. 用户表达购买意图 → 大模型调用 `prePlaceOrder` 工具，工具从上下文拿到 userId 注入 `UserContext`，再 Feign 调用 trade-service 预下单。
2. 预下单结果（金额/优惠/订单 id）存入 `ToolResultHolder`，流结束前由 `ChatServiceImpl` 以 `PARAM` 事件回填前端，供渲染下单确认卡片。
3. requestId 串联同一请求内的工具结果，userId 透传解决工具调用链路的身份认证。

### 安全核查
无密钥、无本地绝对路径、无旧品牌字样。

---

## 2026-11-18（历史消息保留工具外部参数）

> 推送于 2026-11-18。修复「查看历史会话时丢失课程卡片 / 订单卡片」的问题：把工具调用产生的结构化参数随助手消息一起持久化。

**本次推送内容：工具外部参数写入会话历史并随历史消息回传前端，同时修复两处工具类隐患**

### 新增（1 个文件）
- `aigc/memory/MyAssistantMessage.java` — 继承 `AssistantMessage`，扩展 `params` 字段，使助手消息可携带工具产生的结构化数据（课程/订单卡片）

### 修改（9 个文件）
- `aigc/service/impl/ChatServiceImpl.java` — 流结束（finishReason=STOP）时以 messageId 为 key 存 `requestId` 到 `ToolResultHolder`；`.toolContext()` 透传 requestId + userId；工具结果仍以 PARAM 事件回填前端
- `aigc/memory/MessageUtil.java` — `toJson()` 存历史时按 messageId→requestId 取出 params 写入 `MyMessage` 并清理 messageId 映射；`toMessage()` 反序列化助手消息时用 `MyAssistantMessage` 还原 params
- `aigc/service/impl/ChatSessionServiceImpl.java` — 读取历史消息时，若为 `MyAssistantMessage` 则把 params 一并放入 `MessageVO` 返回前端
- `aigc/constants/Constant.java` — 新增 `STOP`、`ID` 常量
- `aigc/config/SpringAIConfig.java` — **修复隐患**：移除重复的 `.defaultTools(courseTools)`，只保留 `.defaultTools(courseTools, orderTools)`
- `aigc/config/ToolResultHolder.java`、`aigc/tools/CourseTools.java`、`aigc/tools/OrderTools.java`、`aigc/tools/result/CourseInfo.java`、`aigc/tools/result/PrePlaceOrder.java` — 随参数持久化链路调整（结果存入 holder 供历史消息读取）

### 工作原理
1. 工具（CourseTools / OrderTools）把结果以 requestId 为 key 存入 `ToolResultHolder`，同时当前流以 PARAM 事件实时回填前端。
2. 大模型输出结束（STOP）时，把「本次消息 messageId → requestId」的映射也存入 holder。
3. 会话记忆持久化（`MessageUtil.toJson`）时：用 messageId 查到 requestId，再用 requestId 取出 params，写入 `MyMessage.params` 一起存进 Redis；随后清理临时映射。
4. 回放历史（`MessageUtil.toMessage` + `ChatSessionServiceImpl.queryBySessionId`）时：用 `MyAssistantMessage` 还原 params，经 `MessageVO.params` 返回前端，历史消息也能渲染课程/订单卡片。

### 安全核查
无密钥、无本地绝对路径、无旧品牌字样。

---

## 2026-11-21（RAG 增强：向量存储 + QuestionAnswerAdvisor）

> 推送于 2026-11-21。本次为 RAG（检索增强生成）能力接入，含**详细代码分析**与**待修复问题清单**。

---

### 一、本次推送内容

**新增依赖（pom.xml）**
- `spring-ai-starter-vector-store-redis` — Spring AI Redis 向量存储 starter
- `jedis` — Redis Java 客户端（VectorStore 底层依赖）

**新增文件（2 个）**
- `aigc/controller/EmbeddingController.java` — 向量库管理接口，`@ConditionalOnBean(VectorStore.class)` 条件下才注册
  - `POST /embedding` — 批量写入文档到向量库
  - `GET /embedding` — 文本向量化
  - `DELETE /embedding` — 按 id 删除向量
  - `GET /embedding/search` — 相似度检索（topK=5）
  - `GET /embedding/search/all` — 检索全部（topK=999）
- `aigc/service/impl/ChatServiceeImpl.java` — ChatService 的 RAG 增强实现，替代原 `ChatServiceImpl`

**删除文件（1 个）**
- `aigc/service/impl/ChatServiceImpl.java` — 被 `ChatServiceeImpl` 取代（避免两个 `@Service` 同时实现 `ChatService` 导致启动冲突）

**未推送的"修改"（10 个）**
- SpringAIConfig / ToolResultHolder / Constant / MessageUtil / MyAssistantMessage / ChatSessionServiceImpl / CourseTools / OrderTools / CourseInfo / PrePlaceOrder：经 `diff --strip-trailing-cr` 核验，与远端内容**完全一致**，仅 CRLF/LF 行尾符差异，故未推送。

---

### 二、RAG 设计与数据流分析

**核心链路**
1. 离线灌库：`EmbeddingController.saveVectorStore()` 把文本转成 `Document` 写入 Redis VectorStore。
2. 在线检索：`ChatServiceeImpl` 构造 `QuestionAnswerAdvisor`，配置相似度阈值 0.6、topK=6，随 prompt 注入 Advisor。
3. 增强生成：用户提问时，Advisor 先从向量库召回相关文档，拼接进上下文，再交给大模型生成答案——答案基于私有知识，而非仅靠模型参数。

**关键设计点**
- `@ConditionalOnBean(VectorStore.class)`：向量库 Bean 不存在时 Controller 不注册，服务仍可启动（优雅降级思路是对的）。
- Advisor 放在 `.advisors(advisor -> advisor.advisors(qaAdvisor).param(...))`：与 ChatMemory Advisor 共存，检索增强 + 多轮记忆叠加。
- 保留了之前所有能力：SSE 流式、停止生成、Function Calling 工具、历史消息参数回填。

---

### 三、⚠️ 待修复问题清单（本次按原样推送，代码当前无法编译/启动）

**P0 — 编译失败：`EmbeddingController.java`**
1. 缺少 import：`@GetMapping`、`@DeleteMapping`、`SearchRequest`、`EmbeddingResponse` 均未导入。
2. `embed()` 方法调用 `this.embeddingModel.embedForResponse(...)`，但类中**未声明 `embeddingModel` 字段**（只声明了 `vectorStore`），需要注入 `EmbeddingModel`。

**P0 — 启动失败：`ChatServiceeImpl.java`**
3. 类名拼写：`ChatServiceeImpl`（多一个 e），建议改回 `ChatServiceImpl`。
4. `ragEnabled` 字段声明为 `private final boolean` 但从未使用；`@RequiredArgsConstructor` 会把它加入构造器，Spring 注入 boolean 类型 Bean 会失败。
5. `qaAdvisor` 是实例字段初始化器，引用的 `this.vectorStore` 在 Lombok 构造器注入 final 字段**之前**执行，此时 `vectorStore` 为 null，构建 Advisor 会 NPE。应改为在构造器内或 `@PostConstruct` 中初始化，并判空。
6. `vectorStore` 为 final 强依赖：未配置 VectorStore 时 `ChatServiceeImpl` 无法注入，与 `@ConditionalOnBean` 的优雅降级设计矛盾。建议用 `ObjectProvider<VectorStore>` 或 `@Autowired(required=false)`。

**P1 — 配置**：Redis VectorStore 需要连接配置（spring.ai.vectorstore.redis.*），请确认 Nacos 配置已就绪。

---

### 四、建议修复顺序
1. 补全 `EmbeddingController` 的 import + 注入 `EmbeddingModel`。
2. `ChatServiceeImpl` 改名 `ChatServiceImpl`，删除 `ragEnabled`，用 `ObjectProvider<VectorStore>` 延迟获取，在构造器内判空后再建 `QuestionAnswerAdvisor`。
3. 本地编译通过后再推一次修复版。

---
