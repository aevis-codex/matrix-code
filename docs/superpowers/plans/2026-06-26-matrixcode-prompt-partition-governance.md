# MatrixCode 第 64 阶段计划：Prompt 编排器与稳定前缀分区治理

## 目标

把 Prompt 稳定前缀治理从字符串约定推进到可机读、可持久化、可审计的分区 trace，为后续继续压榨供应商 prompt cache 命中率提供基础。

## 步骤

- [x] 1. 红测：Prompt 分区结构、usage 分区 trace、仓储保存恢复、Agent Runtime trace、前端展示。
- [x] 2. 后端实现：`PromptPartition`、`PromptContract` 分区字段、builder 分区编排、usage trace 扩展。
- [x] 3. 持久化实现：Flyway `V64_1`、MyBatis-Plus/JDBC 映射、仓储测试。
- [x] 4. 前端实现：API 类型、运行中心 Prompt 分区短线索展示。
- [x] 5. 验证：目标测试、全量测试、构建、真实集成、静态检查和密钥扫描。
- [x] 6. 第二大脑：更新项目首页、阶段索引、模块地图、技术栈、模型网关专题和风险。
- [x] 7. 提交并推送到 `origin master`。

## 回溯检查点

- 多人实时协作智能体控制台：不变，本阶段只强化模型成本和提示词治理能力。
- 每个角色独立智能体：不变，角色提示词仍作为动态分区进入完整 system prompt。
- 成本优化主线：继续参考 DeepSeek-Reasonix / DeepSeek KV Cache，固定稳定前缀，把动态上下文后置。
- 安全边界：只保存分区元数据，不保存 prompt 正文、密钥、模型响应或工具输出。
