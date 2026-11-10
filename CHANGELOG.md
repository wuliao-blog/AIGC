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
