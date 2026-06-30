# MatrixCode 生产发布回滚门禁设计

## 背景

阶段 136 已补齐服务器侧受控重启门禁，但生产发布仍需要一个可验证的应用目录回滚入口。仅在运行手册中写 `cp -a` 容易出现目标目录写错、上一版目录结构不完整或缺少显式确认的问题。

## 目标

- 新增服务器本机执行的发布回滚脚本。
- 真实回滚必须设置 `MATRIXCODE_ROLLBACK_CONFIRM=true`。
- dry-run 能校验上一版目录和目标目录，不修改文件。
- 真实回滚前把当前目标目录备份为 `.failed.YYYYMMDDHHMMSS`。
- 回滚脚本进入发布包、生产资产门禁和聚合 readiness。

## 非目标

- 不自动恢复 MySQL 或 Milvus。
- 不删除失败版本目录。
- 不远程 SSH。
- 不自动重启服务，回滚后仍使用第 136 阶段的受控重启脚本。

## 方案

新增 `scripts/rollback-production-release.sh`：

- 第一个参数：上一版发布目录，例如 `/opt/matrixcode.previous.20260629170000`。
- 第二个参数：目标目录，默认 `/opt/matrixcode`。
- `MATRIXCODE_ROLLBACK_DRY_RUN=true`：只校验并输出计划。
- `MATRIXCODE_ROLLBACK_CONFIRM=true`：允许真实回滚。

安全约束：

- 目标目录拒绝 `/`、`/tmp`、`/var`、`/var/backups`、`/opt` 和仓库根目录。
- 上一版目录必须存在，并包含 `matrixcode-server.jar`、启动脚本和生产 env 示例。
- 上一版目录不能与目标目录相同。

## 验证

```bash
bash scripts/rollback-production-release-test.sh
bash scripts/package-production-release-test.sh
bash scripts/verify-production-deployment-assets.sh
bash scripts/verify-production-readiness.sh
git diff --check
```

## 回溯

- 与第 126 到 136 阶段的发布链路对齐：构建、归档、复制、安装、重启、回滚均有脚本门禁。
- 与生产安全要求一致：真实回滚需要显式确认，数据库恢复仍保留人工高风险流程。
- 与可上线目标一致：服务异常时有可执行、可验证、可审计的应用版本回退路径。
