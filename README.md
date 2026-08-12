# Diet Agent 🍽️

智能饮食推荐系统，基于多 Agent 协作架构实现多轮对话式餐食推荐。用户可以通过自然语言描述口味偏好、餐次、健康目标等需求，系统会智能理解意图、补全信息、检索匹配并生成个性化的饮食建议。

## 核心特性

- **多 Agent 协作编排** — IntentAgent（意图识别）→ ClarifyAgent（槽位补全）→ MealSearch（粗召回）→ MealRank（精排）→ RecommendResponseAgent（推荐理由生成）→ RiskGuard（安全审查），全链路可追踪
- **多轮槽位对话** — 支持渐进式信息补全，用户说"晚饭清淡点"后再说"换成辣的"，系统自动合并历史偏好与新需求
- **双数据源模式** — PERSONAL（个人餐食库，自定义食堂/外卖菜单）和 PUBLIC（公共餐食库），可随时切换
- **多餐规划** — 一键规划早/中/晚三餐，每餐独立检索与推荐
- **换一批 / 调整偏好** — 支持"换一批""清淡点""快点"等自然语言调整，已推荐过的菜自动排除
- **健康风险守卫** — 自动拦截医疗承诺、极端节食等高风险回复，返回安全引导语
- **链路可观测** — 每次请求生成唯一 traceId，记录完整状态机事件到 `agent_traces` 表，便于调试和评估
- **评估体系** — 内置 LLM Judge 评估管线，支持自动标注和人工反馈

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 3.3.13 |
| 持久层 | MyBatis 3.0 + MySQL 8 |
| Agent 框架 | [AgentScope](https://github.com/modelscope/agentscope) 1.0.11 |
| LLM | 通义千问（qwen-max / qwen-turbo）via DashScope |
| 工具库 | Hutool 5.8、Lombok |
| 构建 | Maven |

## 项目结构

```
src/main/java/com/diet/
├── DietApplication.java              # 应用入口
├── agent/
│   ├── builder/                      # Agent Builder（Intent/Clarify/Recommend/Plan/EvaluationJudge）
│   ├── factory/AgentFactory.java     # Agent 工厂
│   └── loader/PromptLoader.java      # Prompt 模板加载器
├── config/
│   └── DietAgentScopeConfig.java     # AgentScope 模型配置（主模型/轻量模型）
├── constants/DietConstants.java      # 全局常量
├── controller/
│   ├── chat/DietChatController.java          # 对话接口 POST /api/v1/diet/chat
│   ├── evaluation/EvaluationController.java  # 评估接口 POST /api/v1/diet/evaluations
│   ├── feedback/FeedbackController.java      # 反馈接口
│   ├── meal/MealController.java              # 餐食 CRUD /api/v1/diet/meals
│   ├── session/SessionController.java        # 会话管理 POST /api/v1/diet/sessions
│   ├── slot/SlotOptionController.java        # 槽位选项查询
│   └── trace/AgentTraceController.java       # 链路追踪查询
├── enums/                            # Intent / ClarifyAction / RiskLevel / SessionPhase / SourceMode
├── exception/                        # 全局异常处理
├── mapper/                           # MyBatis Mapper 接口
├── model/                            # 数据模型（ChatRequest/Response, IntentResult, SlotBundle 等）
├── service/
│   ├── orchestrator/DietOrchestratorService.java  # 🔑 核心编排服务（状态机）
│   ├── intent/                       # 意图识别 + 意图矫正
│   ├── clarify/                      # 澄清追问
│   ├── meal/                         # 餐食检索 / 重排 / 管理
│   ├── recommend/                    # 推荐应答 Agent
│   ├── plan/                         # 多餐规划
│   ├── risk/RiskGuardService.java    # 健康风险守卫
│   ├── session/                      # 会话与状态管理
│   ├── slot/                         # 槽位合并 / 选项管理
│   ├── evaluation/                   # LLM Judge 评估
│   ├── feedback/FeedbackService.java # 用户反馈收集
│   └── trace/AgentTraceService.java  # 链路 Trace
└── util/                             # JSON/LlmJson 工具类
```

## 架构概览

```
用户消息 → DietChatController
              │
              ▼
     DietOrchestratorService（状态机编排）
              │
     ┌───────┼────────┐
     ▼       ▼        ▼
  IntentAgent  →  IntentRevise  →  路由分发
     │                                  │
     ├─ MEAL_RECOMMENDATION ────────────┤
     ├─ MEAL_ADJUST ────────────────────┤
     ├─ MEAL_PLAN ──────────────────────┤
     ├─ HEALTH_RISK → 安全引导回复      │
     └─ OTHER → 固定引导回复            │
                │
        ┌───────┼────────┐
        ▼       ▼        ▼
   SlotMerge  ClarifyAgent  → 追问 or 推荐流水线
                                   │
                          ┌────────┼────────┐
                          ▼        ▼        ▼
                      MealSearch  MealRank  RecommendResponseAgent
                          │        │        │
                          └────────┼────────┘
                                   ▼
                              RiskGuard
                                   │
                                   ▼
                            SessionState 落库 + ChatResponse 返回
```

## 快速开始

### 环境要求

- JDK 21+
- MySQL 8.0+
- Maven 3.8+

### 1. 创建数据库

MySQL 中创建数据库（应用启动时会自动建表，如已配置 `createDatabaseIfNotExist=true`）：

```sql
CREATE DATABASE IF NOT EXISTS diet_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

### 2. 配置应用

复制并编辑本地配置文件（该文件已在 `.gitignore` 中排除）：

```bash
cp src/main/resources/application.yml src/main/resources/application-local.yml
```

修改 `application-local.yml` 中的数据库连接信息和 DashScope API Key：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/diet_db?...
    username: your_username
    password: your_password

agentscope:
  dashscope:
    api-key: your-dashscope-api-key

diet:
  llm:
    main-model: qwen-max      # 推荐应答使用，需较强推理能力
    light-model: qwen-turbo   # 意图识别/澄清使用，降低延迟
```

### 3. 启动应用

```bash
# 指定本地 profile 启动
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 或先打包再运行
mvn clean package -DskipTests
java -jar target/diet-agent-1.0-SNAPSHOT.jar --spring.profiles.active=local
```

应用默认运行在 `http://localhost:8080`。

### 4. 初始化餐食数据

启动后，可以通过 API 录入个人餐食或导入公共餐食数据：

```bash
# 创建会话
curl -X POST http://localhost:8080/api/v1/diet/sessions \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1"

# 添加个人餐食
curl -X POST http://localhost:8080/api/v1/diet/meals/personal \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{
    "name": "番茄炒蛋",
    "mealTime": "午餐",
    "taste": "清淡",
    "cuisine": "家常",
    "convenience": "快"
  }'

# 发起饮食推荐对话
curl -X POST http://localhost:8080/api/v1/diet/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{
    "sessionId": "<上面返回的 sessionId>",
    "message": "晚饭想吃点清淡的，有什么推荐？",
    "sourceMode": "PERSONAL"
  }'
```

## API 接口

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/diet/chat` | 核心对话接口，返回推荐结果或澄清追问 |
| POST | `/api/v1/diet/sessions` | 创建新会话 |

### 餐食管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/diet/meals/personal` | 查询个人餐食列表 |
| POST | `/api/v1/diet/meals/personal` | 添加个人餐食 |
| PUT | `/api/v1/diet/meals/personal/{id}` | 修改个人餐食 |
| DELETE | `/api/v1/diet/meals/personal/{id}` | 删除个人餐食 |
| GET | `/api/v1/diet/meals/public` | 查询公共餐食列表 |

### 评估 & 反馈

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/diet/evaluations` | 触发 LLM Judge 评估 |
| POST | `/api/v1/diet/feedback` | 提交用户反馈 |

## 核心设计

### 意图识别 & 槽位填充

系统定义了 6 种用户意图：

- `MEAL_RECOMMENDATION` — 请求餐食推荐
- `CLARIFY_NEEDED` — 信息不足，需要追问
- `MEAL_ADJUST` — 调整上轮推荐（换一批/清淡点/快点）
- `MEAL_PLAN` — 多餐规划（早中晚）
- `HEALTH_RISK` — 涉及健康风险
- `OTHER` — 与饮食无关

槽位维度包括：餐次（mealTime）、口味（taste）、菜系（cuisine）、场景（scene）、健康目标（healthGoal）、便捷度（convenience）、心情（mood）。

### 推荐流水线

1. **粗召回** — MySQL JSON_OVERLAPS 按槽位多维度匹配，最多 50 条
2. **精排** — 7 维 overlap 打分 + 排除已推荐 ID，取 Top 10
3. **LLM 生成** — Top 3 传给 qwen-max，生成推荐理由和口语化回复
4. **安全审查** — RiskGuard 扫描回复内容，拦截高风险表述

### 评估体系

支持离线评估和在线反馈双通道：
- **LLM Judge** — 自动评估推荐质量、意图准确性、回复安全性
- **用户反馈** — 收集用户对推荐结果的满意度评价

## 许可证

[MIT](LICENSE)
