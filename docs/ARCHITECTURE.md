# 《电商运营与智能问数中台系统》系统架构与技术设计说明书

## 1. 系统总体架构

系统采用清晰的分层架构（六层模型），实现了表现层、业务层、智能体编排层、安全层与持久化层的解耦：

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. 表现交互层 (Presentation Layer)                                    │
│    - 现代化管理中台 SPA (HTML5 + CSS3 + 纯原生 ES6)                     │
│    - ECharts 5 动态数据可视化引擎                                      │
│    - 全局 AI Copilot 侧边抽屉与 BYOK 密钥配置模态框                    │
├────────────────────────────────────────────────────────────────────────┤
│ 2. 接入控制层 (API Gateway / Controller Layer)                         │
│    - ProductController / OrderController / MetricDictionaryController  │
│    - AskController / HealthController / ApiExceptionHandler            │
├────────────────────────────────────────────────────────────────────────┤
│ 3. 业务服务与智能体编排层 (Service & Orchestration Layer)              │
│    - ProductService / OrderService 传统中台业务逻辑                    │
│    - Text2SqlOrchestrator 智能体流水线 (多轮指代消解、上下文压缩、自省重试)│
├────────────────────────────────────────────────────────────────────────┤
│ 4. 知识与检索核心层 (Knowledge & Retrieval Layer)                      │
│    - JoinPathPlanner: 基于加权外键图规划的最短联通路径修复              │
│    - LexicalSchemaRetriever: BM25 + 领域词典最大匹配动态表召回          │
│    - MetricRegistry: 业务口径注入 (解耦计算规则与大模型提示词)          │
├────────────────────────────────────────────────────────────────────────┤
│ 5. 生产级安全护栏层 (Security Guardrails Layer)                        │
│    - JSqlParser AST 语法树解析与列级只读白名单校验                      │
│    - EXPLAIN 查询计划代价评估 (Cost Guard) 防笛卡尔积慢查               │
│    - 只读物理账号沙箱 (text2sql_ro) 物理隔离                            │
├────────────────────────────────────────────────────────────────────────┤
│ 6. 存储与数仓持久化层 (Storage & Persistence Layer)                    │
│    - 主业务数仓: PostgreSQL 16 (37 张真实电商数仓表)                   │
│    - 语义向量缓存: pgvector + L1 进程内 LRU 双级缓存                    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 核心技术抓手

1. **加权外键图（JoinPathPlanner）**：
   - 解决纯文本词法召回时“表之间不连通”的痛点。当用户提问未显式提及桥接表时（例如从 `regions` 跨到 `orders`），算法自动以最短距离在关系图上寻找并补全中间过渡表，确保生成的 SQL 具备合法的 JOIN 拓扑。
2. **业务口径解耦（MetricRegistry）**：
   - 将 GMV、客单价、复购率等复杂指标维护于 `metrics.yml` 中。提问时自动识别关键词并精准注入 Prompt，避免大模型“凭空猜测”业务统计口径。
3. **AST 列级安全白名单**：
   - 任何由大模型生成的 SQL 文本，在物理执行前必须被 JSqlParser 转换为抽象语法树（AST），严格检查根节点类型必须为 `PlainSelect`，且严禁出现写操作、系统函数（`pg_sleep`）或跨库授权。
