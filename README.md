# CodeReviewer - 基于 SpringAI 的智能代码审查 Agent

> 个人学习/求职项目，使用 Spring Boot 3 + Spring AI 1.0 构建的多维度代码审查 Agent，
> 在 Git 仓库产生 PR/MR 时自动触发，结合团队规范知识库与历史 Issue，
> 调用 LLM 完成结构化代码审查并回写到 PR 评论。

## 系统架构

```
┌────────────┐    webhook    ┌──────────────────┐
│ Gitea       │──────────────▶│  Webhook 接收层  │
└────────────┘               └────────┬─────────┘
                                      │ diff + 元数据
                                      ▼
                       ┌─────────────────────────────┐
                       │   RabbitMQ 异步队列          │
                       └─────────────┬───────────────┘
                                     ▼
┌────────────────────────────────────────────────────────────┐
│              CodeReviewer Agent Core                       │
│                                                            │
│  1. 拉取 diff (GitIntegrationService)                      │
│  2. DiffChunker 切分 (按文件→hunk→滑动窗口)               │
│  3. ChatClient + Advisor 链 (6 层：自研 3 + 内置 3)       │
│     ┌──────────────────────────────────────────────┐       │
│     │ SimpleLoggerAdvisor      内置 · 基础日志           │       │
│     │ ToolCallLogAdvisor       自研 · 工具调用追踪       │       │
│     │ MessageChatMemoryAdvisor 内置 · 对话历史注入       │       │
│     │ QuestionAnswerAdvisor    内置 · RAG 检索           │       │
│     │ SensitivityFilterAdvisor 自研 · 敏感词过滤         │       │
│     │ TokenLimitAdvisor        自研 · token 截断         │       │
│     └──────────────────────────────────────────────┘       │
│  4. Function Calling (4 个工具，LLM 自主决策)              │
│  5. SSE 流式输出                                          │
│  6. 回写 PR 评论                                           │
│                                                            │
│  + Observation/Micrometer (token 消耗/调用链/P99)          │
│  + Redis 令牌桶限流                                        │
│  + 多模型降级 (DeepSeek/通义千问)                          │
└────────────────────────────────────────────────────────────┘
        │                              │
        ▼                              ▼
┌──────────────┐              ┌─────────────────────┐
│ PostgreSQL   │              │  Redis              │
│ + pgvector   │              │  ChatMemory         │
│ 业务+向量    │              │  + 限流计数         │
└──────────────┘              └─────────────────────┘
        │
        ▼
┌──────────────────────────┐
│ BGE-Reranker (Docker)    │
│ 重排序服务                │
└──────────────────────────┘
```

## 技术栈

| 类别 | 技术 |
|---|---|
| JDK | 17 |
| 框架 | Spring Boot 3.3.5 / Spring AI 1.0.0 GA |
| LLM | DeepSeek (主) / 通义千问 (备) |
| 向量库 | PostgreSQL 15 + pgvector |
| 缓存 | Redis 7 (对话记忆 + 限流) |
| 消息队列 | RabbitMQ 3.13 |
| 数据库迁移 | Flyway |
| Token 估算 | JTokkit (tiktoken Java 实现) |
| 可观测性 | Micrometer + Prometheus |
| 重排序 | BGE-Reranker-base (text-embeddings-inference) |
| Git 平台 | Gitea |
| 部署 | Docker Compose |

## 简历亮点（对应代码模块）

| 亮点 | 模块 | 文件 |
|---|---|---|
| SSE 流式输出 + 会话隔离 | ChatController | `controller/ChatController.java` |
| Advisor 责任链 (6 层) | ChatClientConfig + advisor/ | `config/ChatClientConfig.java` |
| Function Calling (4 个工具) | CodeReviewTools | `tool/CodeReviewTools.java` |
| RAG 知识库 + 重排序 | KnowledgeBaseService + RerankService | `service/rag/` |
| Diff 智能分块 | DiffChunker | `service/diff/DiffChunker.java` |
| ChatMemory 多轮对话 | 配置 + ChatController | `application.yml` |
| 限流降级 (令牌桶) | RateLimiter (Lua 脚本) | `service/ratelimit/RateLimiter.java` |
| 可观测性 | CodeReviewService (Micrometer) | `service/agent/CodeReviewService.java` |
| 多模型适配 | 通过 SpringAI 抽象层 | `application.yml` |
| 工具调用链路追踪 | ToolCallLogAdvisor | `advisor/ToolCallLogAdvisor.java` |

## 快速开始

### 1. 准备 API Key
- DeepSeek API Key：https://platform.deepseek.com
- Gitea Token（可选，用于 webhook）

### 2. 启动依赖

```bash
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY

docker-compose up -d postgres redis rabbitmq
# 可选：启动 Gitea 本地测试平台
# docker-compose up -d gitea
```

### 3. 启动应用

```bash
# 本地运行
mvn spring-boot:run

# 或打包
mvn clean package -DskipTests
java -jar target/code-reviewer-1.0.0.jar
```

### 4. 访问演示界面

打开浏览器访问：http://localhost:9090/code-reviewer/

- 粘贴代码 diff → 点击"开始审查"，可看到 SSE 流式输出
- 或点击"启动对话"体验多轮对话追问

### 5. 通过 webhook 触发

在 Gitea/GitLab 仓库设置中：
- Webhook URL：`http://your-host:9090/code-reviewer/api/webhook/gitea`
- 触发事件：Pull request
- 密钥：与 `.env` 中 `WEBHOOK_SECRET` 一致

## 关键 API

### 代码审查（流式）
```
POST /api/chat/review?sessionId=xxx
Content-Type: text/plain

（请求体为 unified diff 文本）
```

### 对话追问（SSE）
```
GET /api/chat/{sessionId}?message=你的问题
Accept: text/event-stream
```

### 知识库管理
```
POST /api/admin/standards        # 添加规范文档
POST /api/admin/knowledge/index  # 触发向量化索引
GET  /api/admin/rag/search?query=xxx  # 检索测试
GET  /api/admin/reviews          # 查询历史审查记录
GET  /api/admin/tool-calls       # 查询工具调用日志
```

## 项目结构

```
src/main/
├── java/com/example/reviewer/
│   ├── CodeReviewerApplication.java
│   ├── config/             # 配置类
│   ├── advisor/            # 自定义 Advisor (4 个)
│   ├── common/             # TokenCounter、异常处理
│   ├── controller/         # 接口层 (3 个)
│   ├── consumer/           # MQ 消费者
│   ├── model/
│   │   ├── dto/            # DiffChunk、ReviewRequest、WebhookPayload
│   │   └── entity/         # JPA 实体 (4 个)
│   ├── repository/         # JPA Repository
│   ├── service/
│   │   ├── agent/          # CodeReviewService 核心
│   │   ├── diff/           # DiffChunker
│   │   ├── git/            # GitIntegrationService
│   │   ├── prompt/         # PromptBuilder
│   │   ├── rag/            # RAG + Rerank
│   │   └── ratelimit/      # 令牌桶
│   └── tool/               # Function Calling 工具
├── resources/
│   ├── application.yml
│   ├── prompts/            # Prompt 模板
│   ├── db/migration/        # Flyway SQL
│   └── static/              # 演示页面
```

## 关键设计说明

### Advisor 责任链

链上共 6 层，其中自研 3 层、SpringAI 内置 3 层：

| 顺序 | Advisor | 来源 | 职责 |
|---|---|---|---|
| 1 | SimpleLoggerAdvisor | 内置 | 请求/响应基础日志 |
| 2 | ToolCallLogAdvisor | 自研 | 工具调用追踪并落库 |
| 3 | MessageChatMemoryAdvisor | 内置 | 注入对话历史（滑窗 maxMessages=20） |
| 4 | QuestionAnswerAdvisor | 内置 | RAG 检索规范片段，隐式注入上下文 |
| 5 | SensitivityFilterAdvisor | 自研 | 过滤 API Key / 密码 / 手机号 / 身份证 |
| 6 | TokenLimitAdvisor | 自研 | 估算 token 并截断超限输入 |

**顺序设计考虑**：敏感词过滤（5）必须先于 token 截断（6），
否则密钥字符串可能被截断成碎片，正则失配后泄漏给模型。

> `QuestionAnswerAdvisor` 依赖 `VectorStore`，dev 模式下无 PgVector 时自动跳过（见 `ChatClientConfig`）。

### RAG 与 Function Calling 的分工

两者都能拿规范，因此明确划清边界避免 LLM 决策摇摆：

- **RAG（QuestionAnswerAdvisor）**：隐式注入。每次调用自动附带 Top-K 相关规范，覆盖高频通用场景，无需 LLM 决策。
- **@Tool 工具（CodeReviewTools）**：显式检索。LLM 判断上下文不足时主动多次查询，用于精确检索与历史 Issue 关联。

### 质量保障

- Diff 分块、令牌桶限流等纯逻辑模块有单元测试（`src/test`），可执行 `mvn test` 验证。
- GitHub Actions 在 push / PR 时自动构建。


### SpringAI 核心概念对应
| SpringAI 概念 | 本项目位置 | 作用 |
|---|---|---|
| ChatClient | ChatClientConfig | LLM 调用入口 |
| Advisor | advisor/ | 请求/响应拦截（AOP） |
| @Tool | CodeReviewTools | Function Calling 工具定义 |
| VectorStore | KnowledgeBaseService | 向量检索 |
| Embedding | 自动注入 | 文本向量化 |
| ChatMemory | MessageChatMemoryAdvisor | 对话历史 |
| PromptTemplate | prompts/ + PromptBuilder | Prompt 工程化 |
| Observation | CodeReviewService | 调用链追踪 |

### 延伸阅读主题

1. **SSE 流式输出原理**：背压、Token 边界、心跳保活、断线重连
2. **Advisor 责任链**：order 顺序、对同步/流式两种调用的处理
3. **Function Calling 原理**：LLM 如何决策调用工具？JSON Schema 是怎么生成的？
4. **RAG 检索召回率优化**：切分策略、TopK 选择、为什么需要重排序
5. **令牌桶算法**：与漏桶区别、Lua 脚本原子性
6. **Diff 切分策略**：为什么要切？切多细？Token 怎么估？
7. **多轮对话上下文管理**：滑动窗口、Token 超限怎么处理

## 开发路线图

- [x] 阶段 1：MVP - webhook → diff 切分 → LLM 审查 → 评论回写
- [x] 阶段 2：Function Calling 工具集
- [x] 阶段 3：RAG 知识库 + 重排序
- [x] 阶段 4：Advisor 链 + 可观测性 + 限流降级
- [ ] 阶段 5：多 Agent 协作（Planner / Reviewer / Reporter）
- [ ] 阶段 6：SonarQube 完整集成
- [ ] 阶段 7：前端可视化（Vue3 + 实时调用链展示）

## 已知限制

1. Git webhook 解析做了简化，实际项目需根据 GitLab/Gitea payload 完整解析
2. BGE-Reranker 服务首次启动会拉取模型，需要外网或预下载
3. 当前 RAG 用 `text-embedding-3-small`，DeepSeek 暂未开放 embedding 接口，需用 OpenAI 兼容路径

## License

MIT - 个人学习项目，可自由使用
