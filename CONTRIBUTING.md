# 贡献指南 (Contributing Guide)

欢迎为 **Text2SQL-apply (电商运营与智能问数中台系统)** 贡献代码与文档！

## 1. 提交规范 (Commit Convention)

本项目严格遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

- `feat`: 新增业务功能或核心智能体组件（如新算子、新检索器）
- `fix`: 修复 Bug 或异常处理
- `docs`: 文档变更或架构说明更新
- `style`: 代码格式调整（不影响业务逻辑）
- `refactor`: 重构代码（不引入新功能也不修复 Bug）
- `perf`: 性能调优（如降低检索延迟、优化冷启动探针）
- `test`: 新增或修改单元测试与对抗测试集

## 2. 分支管理策略

- `main` / `master`：生产主分支，保障随时可编译、测试通过且可执行
- `feature/*`：功能开发特性分支
- `bugfix/*`：缺陷修复分支

## 3. 本地开发与代码门禁

在提交 Pull Request 前，请确保本地通过全量测试并构建成功：

```bash
# 运行全量单元测试与对抗安全测试 (253+ test cases)
mvn clean test

# 验证可执行 FatJar 打包
mvn package -DskipTests
```

## 4. 架构设计原则

1. **确定性优先**：业务口径优先使用规则引擎与指标字典拦截，禁止完全依赖 LLM 随机发散。
2. **安全左移**：所有 SQL 在发往数据库前必须经过 JSqlParser AST 白名单沙箱与行数限制注入。
3. **高可解释性**：所有设计必须能通过多层工程追问，提供完整的指标、日志与流转图。
