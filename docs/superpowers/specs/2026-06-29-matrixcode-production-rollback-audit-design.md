# MatrixCode 生产发布回滚审计日志设计

## 背景

第 137 阶段已经补齐服务器侧发布回滚门禁，但真实回滚完成后缺少机器可读审计记录。仅依赖 shell 输出和 systemd 日志，不利于后续接入平台部署记录、日志采集或问题复盘。

## 目标

- 回滚脚本在配置审计日志路径时追加 JSONL 记录。
- 审计记录包含动作、状态、发生时间、上一版目录、目标目录和失败版本备份目录。
- 审计日志路径由环境变量提供，不把日志写死到脚本。
- 未配置审计日志时保持原有回滚行为。

## 非目标

- 本阶段不直接写平台部署记录；第 142 阶段已补受控导入 API。
- 不记录真实密钥、env 文件内容或业务数据。
- 不替代平台级审批流。

## 方案

新增配置：

- `MATRIXCODE_ROLLBACK_AUDIT_LOG`：回滚审计 JSONL 文件路径。

`rollback-production-release.sh` 在真实回滚成功后调用 `write_audit_record "SUCCEEDED"`：

- 若未配置审计日志路径，直接跳过。
- 若配置了路径但父目录不存在，回滚脚本报错，避免用户以为审计已记录。
- JSON 字段做最小转义，避免路径中的特殊字符破坏 JSONL。

## 验证

```bash
bash scripts/rollback-production-release-test.sh
bash scripts/verify-production-deployment-assets.sh
bash scripts/verify-production-readiness.sh
git diff --check
```

## 回溯

- 与第 137 阶段一致：回滚仍只恢复应用目录，不处理数据库恢复。
- 与可上线目标一致：高风险生产动作开始具备可采集的审计证据。
- 与安全要求一致：审计记录只包含路径、状态和时间，不包含凭据。
