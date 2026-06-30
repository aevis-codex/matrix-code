# MatrixCode 真实上线就绪审计记录

## 2026-06-29：远程上线目标接入审计

### 已通过

- 当前 `master`：`abd657e`
- 生产发布候选包：`matrixcode-stage164-20260629220712.tar.gz`
- 发布包 Jar 本机生产启动 smoke：PASS
- 真实生产预检：PASS，真实 MySQL/Flyway、Milvus、Redis Session 相关集成 3 条通过。
- 本机健康快照：`dist/health-snapshots/matrixcode-stage165-jar-production-smoke.md`
- 远端网络端口探测：`127.0.0.1:22`、`127.0.0.1:80`、`127.0.0.1:443` TCP 可达。

### 当前阻塞

- `.env.local` 未配置 `MATRIXCODE_REMOTE_RELEASE_TARGET`。
- `root@127.0.0.1`、`deploy@127.0.0.1`、`matrixcode@127.0.0.1`、`aevis@127.0.0.1` 的 BatchMode SSH 均返回 `Permission denied`。
- 因缺少可用 SSH 身份，不能执行真实远程目标预检、远程 dry-run 或真实发布。

### 上线恢复条件

- 配置 `MATRIXCODE_REMOTE_RELEASE_TARGET`，例如 `deploy@<server>`。
- 该 SSH 用户需要具备上传发布包、创建发布目录、安装到应用目录、执行 systemd 重启和读取健康端点的最小权限。
- 远端需要准备或允许脚本创建发布目录，建议保持 `/srv/matrixcode/releases`。
- 应用目录建议保持 `/opt/matrixcode`，并与 systemd 模板一致。

### 安全边界

- 本记录不保存 SSH 私钥、密码、数据库密码、API Key、Sa-Token、完整 prompt、模型响应、向量正文或工具输出。
- 端口探测和 SSH BatchMode 登录尝试均为只读，不上传文件、不执行远端发布、不修改远端系统。
