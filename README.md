# tj-aigc · AIGC 服务

`天机学堂` 项目中的 AI 能力微服务，基于 **Spring Boot + Spring AI Alibaba（DashScope 通义千问）**，提供 **SSE 流式对话**、**停止生成** 与 **会话管理** 能力，系统提示词由 **Nacos 配置中心动态下发**。

> 仓库于 2026-11-08 首次提交（基础骨架），此后逐日迭代。完整变更见 [CHANGELOG.md](./CHANGELOG.md)。

## 技术栈

| 组件 | 版本 / 说明 |
|------|-------------|
| JDK | 17 |
| Spring Boot | 3.x（由父工程 `com.tianji:tjxt:1.0.0` 统一管理） |
| Spring AI | BOM 由父工程 `${spring-ai.version}` 指定 |
| Spring AI Alibaba | `1.0.0.2`（`spring-ai-alibaba-starter-dashscope`） |
| 注册 / 配置中心 | Spring Cloud Alibaba Nacos（discovery + config） |
| 存储 | Redis（**Redisson**）、MySQL + MyBatis-Plus |
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

### SSE 流式对话

- `ChatServiceImpl` 调用 `ChatClient.prompt().system(...).user(question).stream().chatResponse()`
- 系统提示词取自 `SystemPromptConfig`（Nacos 动态加载），并通过 `.param("now", DateUtil.now())` 注入当前时间参数，支持提示词模板占位符
- 每段模型输出封装为 `ChatEventVO(eventData=文本, eventType=DATA)`，末尾 `concatWith` 追加 `STOP` 事件
- `ChatEventTypeEnum` 定义 `DATA(1001)` / `STOP(1002)` / `PARAM(1003)`
- Controller 用 `@NoWrapper` 跳过统一响应包装，直接输出事件流

### 停止生成（Redis / Redisson 方案）

这是本模块最值得看的设计——**用一个共享状态位控制 `Flux` 流的中止**：

1. `doFirst` 首段输出前，在 Redis 写入 `aigc:generate:status:{sessionId} = true`，**设置 10 分钟过期**（防止会话异常结束时 key 泄漏）
2. `takeWhile` 每读一段就从 Redis 读该状态，`true` 继续、`false`/不存在则终止流
3. `POST /chat/stop` 调用 `stop(sessionId)` 删除该 key → 下一次 `takeWhile` 检查时流自然终止
4. `doOnError` / `doOnComplete` 负责清理状态

**为什么用 Redis 而不是本地 Map**：初版用 `ConcurrentHashMap` 存状态，仅单机内存有效；分布式部署时，生成请求和停止请求可能落到不同节点，本地 Map 无法跨节点生效。改用 Redisson `RBucket` 后，停止信号存在共享 Redis 中，任意节点调用 `stop()` 都能广播终止。

### 会话管理

- `POST /session` — `IdUtil.fastSimpleUUID()` 生成 `sessionId`，随机抽 n 个示例问题，并把会话（含 `UserContext` 当前用户）持久化到 `chat_session` 表
- `GET /session/hot` — 随机返回 n 个热门示例
- `SessionProperties` 绑定 `tj.ai.session`（`title` / `describe` / `examples`），示例数据来自 Nacos 配置

### ChatClient 装配

`SpringAIConfig` 用 `ChatClient.Builder` 构建 Bean，注册 `SimpleLoggerAdvisor` 做请求/响应日志增强。

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
    │   │   │   ├── SpringAIConfig.java                # ChatClient + SimpleLoggerAdvisor
    │   │   │   ├── AIProperties.java                  # 绑定 tj.ai.prompt.*
    │   │   │   └── SystemPromptConfig.java            # Nacos 拉取提示词 + 热更新
    │   │   └── aigc/
    │   │       ├── config/SessionProperties.java      # tj.ai.session 配置
    │   │       ├── controller/ChatController.java    # POST /chat、POST /chat/stop
    │   │       ├── controller/SessionController.java # /session 系列接口
    │   │       ├── dto/ChatDTO.java
    │   │       ├── entity/ChatSession.java           # chat_session 表
    │   │       ├── enums/ChatEventTypeEnum.java
    │   │       ├── mapper/ChatSessionMapper.java
    │   │       ├── service/ChatService.java
    │   │       ├── service/ChatSessionService.java
    │   │       ├── service/impl/ChatServiceImpl.java # 流式对话 + Redis 停止控制
    │   │       ├── service/impl/ChatSessionServiceImpl.java
    │   │       ├── vo/ChatEventVO.java
    │   │       └── vo/SessionVO.java
    │   └── resources/
    │       ├── application.yml                       # 主配置：端口、DashScope、提示词 dataId
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
- Redis（Redisson 连接信息来自 `shared-redis.yaml`），以及 `chat_session` 表

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
- `tj.swagger.*` 控制 Knife4j 文档开关、扫描包、标题等
- `tj.jdbc.database: tj_aigc` 指定业务库名
- ⚠️ `application.yml` 中 `spring.ai.dashscope.api-key` 为**占位字符串** `sk-placeholder-for-local-dev`，非真实密钥。本地开发请通过环境变量或 Nacos 注入真实 Key，**切勿把真实 API Key 提交进仓库**；仓库内 Nacos 地址与 `nacos/nacos` 口令同为教学环境示例值

## 更新记录

见 [CHANGELOG.md](./CHANGELOG.md)。

## 来源

天机学堂（tjxt）课程项目中的 AIGC 服务模块，本仓库仅收录该模块自身代码，用于学习与记录。
