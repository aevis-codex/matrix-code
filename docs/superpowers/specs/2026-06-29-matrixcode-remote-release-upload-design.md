# MatrixCode 远程发布包复制设计

## 背景

第 126 阶段已经生成发布归档，第 128 阶段已经提供服务器侧安装脚本。真实上线还需要把归档、校验文件和 manifest 复制到服务器。本阶段只解决受控远程复制，不安装、不重启，避免越过运维审批边界。

## 目标

- 新增 `scripts/upload-production-release.sh`。
- 支持 dry-run 校验归档、sha256、远端目标和远端目录。
- 真实复制必须显式设置 `MATRIXCODE_REMOTE_RELEASE_CONFIRM=true`。
- 真实复制只上传 `*.tar.gz`、`*.tar.gz.sha256` 和可选 `*.manifest`。
- 发布包、发布归档检查和聚合门禁纳入该脚本。

## 非目标

- 不执行远端安装。
- 不执行 `systemctl restart`。
- 不执行 `nginx reload`。
- 不保存 SSH 私钥或服务器密码。
- 不替代第 128 阶段服务器侧安装脚本。

## 行为设计

配置项：

- `MATRIXCODE_REMOTE_RELEASE_TARGET`：SSH/SCP 目标，例如 `deploy@example.com`。
- `MATRIXCODE_REMOTE_RELEASE_DIR`：远端暂存目录，默认 `/tmp/matrixcode-releases`。
- `MATRIXCODE_REMOTE_RELEASE_DRY_RUN`：只校验本地归档和输出目标。
- `MATRIXCODE_REMOTE_RELEASE_CONFIRM`：真实复制确认开关。

安全边界：

- 远端目录必须是绝对路径。
- 拒绝 `/`、`/tmp`、`/var`、`/var/backups`、`/opt` 和 `/opt/matrixcode`。
- 远端目标不能包含路径、空格或 shell 分隔符。
- 复制前校验 `*.sha256`。

## 验证

阶段测试：

```bash
bash scripts/upload-production-release-test.sh
```

聚合门禁：

```bash
bash scripts/verify-production-readiness.sh
```
