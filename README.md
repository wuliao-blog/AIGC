# tj-aigc · AIGC 服务

`天机学堂` 项目中的 AI 能力微服务，基于 **Spring Boot + Spring AI Alibaba（DashScope 通义千问）**，提供 **SSE 流式对话** 与 **会话管理** 能力。

> 说明：仓库于 2026-11-08 首次提交时仅含基础骨架；2026-11-10 补齐了业务代码（见 [CHANGELOG.md](./CHANGELOG.md)）。原「Nacos 动态系统提示词」实现目前**已整体注释停用**，代码保留供参考。

## 技术栈

| 组件 | 版本 / 说明 |
|------|-------------|
| JDK | 17 |
| Spring Boot | 3.x（由父工程 `com.tianji:tjxt:1.0.0` 统一管理） |
| Spring AI | BOM 由父工程 `${spring-ai.version}` 指定 |
| Spring AI Alibaba | `1.0.0.2`（`spring-ai-alibaba-starter-dashscope`） |
| 注册 / 配置中心 | Spring Cloud Alibaba Nacos（discovery + config） |
| 存储 | Redis、MySQL + MyBatis-Plus |
| 接口文档 | Knife4j（OpenAPI 3） |
| 流式输出 | Project Reactor `Flux` + SSE（`text/event-stream`） |
| 服务端口 | `8094`，服务名 `aigc-service`，数据库 `tj_aigc` |

## 已实现功能

### SSE 流式对话

`POST /chat`，请求体 `ChatDTO { question, sessionId }`，返回 `Flux<ChatEventVO>`：

- `ChatServiceImpl` 调用 `ChatClient.prompt().user(question).stream().chatResponse()`
- 每一段模型输出封装为 `ChatEventVO(eventData=文本, eventType=DATA)`
- 末尾 `concatWith` 追加一条 `STOP` 事件标记结束
- `ChatEventTypeEnum` 定义 `DATA(1001)` / `STOP(1002)` / `PARAM(1003)`
- Controller 用 `@NoWrapper` 跳过统一响应包装，直接输出事件流

### 会话管理

- `POST /session?n=3` — 新建会话：`IdUtil.fastSimpleUUID()` 生成 `sessionId`，从配置里随机抽 n 个示例问题，并把会话（含 `UserContext` 当前用户）持久化到 `chat_session` 表
- `GET /session/hot?n=3` — 随机返回 n 个热门示例
- `SessionProperties` 绑定 `tj.ai.session`（`title` / `describe` / `examples`），示例数据来自 Nacos 配置

### ChatClient 装配

`SpringAIConfig` 用 `ChatClient.Builder` 构建 Bean，并注册 `SimpleLoggerAdvisor` 做请求/响应日志增强。

## 目录结构

```
tj-aigc/
├── pom.xml
├── README.md
├── CHANGELOG.md
└── src/main/
    ├── java/com/tianji/
    │   ├── AIGCApplication.java                    # 启动类（@EnableAsync）
    │   ├── config/
    │   │   ├── SpringAIConfig.java                # ChatClient + SimpleLoggerAdvisor
    │   │   ├── AIProperties.java                  # ⚠️ 已整体注释停用（原提示词配置）
    │   │   └── SystemPromptConfig.java            # ⚠️ 已整体注释停用（原提示词热更新）
    │   └── aigc/
    │       ├── config/SessionProperties.java      # tj.ai.session 配置
    │       ├── controller/ChatController.java    # POST /chat
    │       ├── controller/SessionController.java # /session 系列接口
    │       ├── dto/ChatDTO.java
    │       ├── entity/ChatSession.java           # chat_session 表
    │       ├── enums/ChatEventTypeEnum.java
    │       ├── mapper/ChatSessionMapper.java
    │       ├── service/ChatService.java
    │       ├── service/ChatSessionService.java
    │       ├── service/impl/ChatServiceImpl.java
    │       ├── service/impl/ChatSessionServiceImpl.java
    │       ├── vo/ChatEventVO.java
    │       └── vo/SessionVO.java
    └── resources/
        ├── application.yml                       # 主配置：端口、服务名、Swagger、数据源
        ├── application-local.yml                 # 本地环境（默认激活）
        ├── application-dev.yml                   # 开发环境
        └── application-test.yml                  # 测试环境
```

## 设计说明：动态系统提示词（当前停用）

初版设计是把 LLM 的 System Prompt 放到 Nacos 配置中心，做到改提示词不重启服务：

1. `AIProperties` 绑定 `tj.ai.prompt.system.chat`（`dataId` / `group` / `timeoutMs`）
2. `SystemPromptConfig` 在 `@PostConstruct` 阶段通过 `NacosConfigManager.getConfigService().getConfig(...)` 拉取
3. `AtomicReference<String>` 保存，保证并发读写安全
4. 注册 Nacos `Listener`，配置变更回调 `receiveConfigInfo` 直接替换内存中的提示词

**这两个类目前已整体注释，机制未接入当前对话链路**（`ChatServiceImpl` 目前直接用 `.user(question)`，未注入系统提示词，也未使用 `sessionId` 做多轮记忆）。代码保留供后续扩展参考。

## 运行前提（重要）

本模块**不能独立构建运行**，依赖同项目（本仓库未包含）的：

- 父工程 `com.tianji:tjxt:1.0.0`（统一依赖版本管理）
- 内部模块 `com.tianji:tj-api:1.0.0`、`com.tianji:tj-auth-resource-sdk:1.0.0`，以及 `tj-common` 中的 `UserContext`、`NoWrapper`、`BaseEnum` 等
- Nacos 共享配置：`shared-spring.yaml`、`shared-redis.yaml`、`shared-logs.yaml`、`shared-feign.yaml`、`shared-mybatis.yaml`、`aigc-service.yaml`（含 DashScope API Key 与 `tj.ai.session` 示例数据）

启动前请按 `application-{profile}.yml` 中的 `server-addr` 部署/调整 Nacos 地址，并建好 `chat_session` 表。

```bash
# 在包含父工程与依赖模块的完整项目中执行
mvn -pl tj-aigc -am clean package
java -jar tj-aigc/target/tj-aigc.jar --spring.profiles.active=local
```

接口文档：`http://localhost:8094/doc.html`

## 配置说明

- `spring.profiles.active` 默认 `local`；`local` / `dev` / `test` 主要差异是 Nacos 地址与注册 IP
- `application-local.yml` 额外导入 `nacos:aigc-service.yaml`（本地服务专属配置）
- `tj.swagger.*` 控制 Knife4j 文档开关、扫描包、标题等
- `tj.jdbc.database: tj_aigc` 指定业务库名
- **密钥不要写进代码或提交进仓库**：DashScope API Key、数据库账号等统一放 Nacos；仓库内 yml 的 Nacos 地址与 `nacos/nacos` 口令为教学环境示例值，生产请替换并妥善保管

## 更新记录

见 [CHANGELOG.md](./CHANGELOG.md)。

## 来源

天机学堂（tjxt）课程项目中的 AIGC 服务模块，本仓库仅收录该模块自身代码，用于学习与记录。
