# tj-aigc · AIGC 服务

`天机学堂` 项目中的 AI 能力微服务，基于 **Spring Boot + Spring AI Alibaba（DashScope 通义千问）**，通过 **Nacos 配置中心动态管理系统提示词（System Prompt）**，做到改提示词不重启服务。

> 当前仓库是该服务的**基础骨架**：包含启动类、配置类、多环境配置与构建文件，业务 Controller / Service 尚未实现（`application.yml` 中 `package-path: com.tianji.aigc.controller` 为预留扫描路径）。

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
| 服务端口 | `8094`，服务名 `aigc-service`，数据库 `tj_aigc` |

## 目录结构

```
tj-aigc/
├── pom.xml
└── src/main/
    ├── java/com/tianji/
    │   ├── AIGCApplication.java              # 启动类（@EnableAsync）
    │   └── config/
    │       ├── AIProperties.java             # 绑定 tj.ai.prompt.* 配置
    │       └── SystemPromptConfig.java       # 从 Nacos 加载系统提示词并监听热更新
    └── resources/
        ├── application.yml                   # 主配置：端口、服务名、Swagger、数据源
        ├── application-local.yml             # 本地环境（默认激活）
        ├── application-dev.yml               # 开发环境
        └── application-test.yml              # 测试环境
```

## 核心设计：系统提示词热更新

这是本模块最值得看的部分——把 LLM 的 System Prompt 放到配置中心，而不是写死在代码里。

1. `AIProperties` 绑定 `tj.ai.prompt.system.chat`，提供 `dataId`、`group`（默认 `DEFAULT_GROUP`）、`timeoutMs`（默认 20000）。
2. `SystemPromptConfig` 在 `@PostConstruct` 阶段通过 `NacosConfigManager.getConfigService().getConfig(...)` 拉取提示词。
3. 用 `AtomicReference<String>` 保存，保证并发读写的线程安全。
4. 注册 Nacos `Listener`，配置变更时回调 `receiveConfigInfo` 直接替换内存中的提示词，**无需重启应用**。

业务代码中注入 `SystemPromptConfig`、调用 `getChatSystemMessage().get()` 即可拿到最新提示词。

## 运行前提（重要）

本模块**不能独立构建运行**，它依赖同项目（本仓库未包含）的：

- 父工程 `com.tianji:tjxt:1.0.0`（统一依赖版本管理）
- 内部模块 `com.tianji:tj-api:1.0.0`、`com.tianji:tj-auth-resource-sdk:1.0.0`
- Nacos 中的共享配置：`shared-spring.yaml`、`shared-redis.yaml`、`shared-logs.yaml`、`shared-feign.yaml`、`shared-mybatis.yaml`，以及 `${spring.application.name}.yaml`（含 DashScope API Key 等敏感项）

启动前请按 `application-{profile}.yml` 中 `server-addr` 部署/调整 Nacos 地址，并在 Nacos 配置好上述共享配置与 `tj.ai.prompt.system.chat` 对应的 dataId。

```bash
# 在包含父工程与依赖模块的完整项目中执行
mvn -pl tj-aigc -am clean package
java -jar tj-aigc/target/tj-aigc.jar --spring.profiles.active=local
```

接口文档：`http://localhost:8094/doc.html`

## 配置说明

- `spring.profiles.active` 默认 `local`；`local` / `dev` / `test` 主要差异是 Nacos 地址与注册 IP。
- `tj.swagger.*` 控制 Knife4j 文档开关、扫描包、标题等。
- `tj.jdbc.database: tj_aigc` 指定业务库名。
- **密钥不要写进代码或提交进仓库**：DashScope API Key、数据库账号等统一放 Nacos 配置中心；仓库内 yml 中的 Nacos 地址与口令为教学环境示例值，生产使用请替换并妥善保管。

## 来源

天机学堂（tjxt）课程项目中的 AIGC 服务模块，本仓库仅收录该模块自身代码，用于学习与记录。
