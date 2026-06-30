# MatrixCode 远程发布目标预检门禁设计

## 背景

第 157 阶段已经生成真实发布候选报告，但由于没有配置 `MATRIXCODE_REMOTE_RELEASE_TARGET`，候选报告中的远程发布 dry-run 被标记为 `SKIPPED`。这不是代码构建问题，而是上线前远程目标配置缺口。为了不把缺失 SSH 目标、目录和服务名的问题留到真实发布时暴露，需要把远程发布目标预检沉淀为脚本门禁。

## 目标

- 提供独立的 `verify-remote-release-target.sh`，验证远程发布目标、发布目录、应用目录、systemd 服务名和健康检查 URL。
- 默认只做本地配置校验，不连接远端、不创建目录、不重启服务。
- 显式开启 `MATRIXCODE_REMOTE_RELEASE_PREFLIGHT_CONNECT=true` 后，用 SSH 做只读命令能力检查。
- 把预检脚本纳入发布包、生产部署资产验证、生产就绪聚合门禁和发布候选报告。

## 非目标

- 不自动执行真实远程发布。
- 不保存 SSH 密钥、sudo 凭据或生产 env 明文。
- 不替代服务器账号授权、sudoers 策略、systemd 权限和网络白名单配置。

## 实现方案

- 新增 `scripts/verify-remote-release-target.sh`：
  - 校验 `MATRIXCODE_REMOTE_RELEASE_TARGET` 非空，拒绝路径、空格和 shell 分隔符。
  - 校验 `MATRIXCODE_REMOTE_RELEASE_DIR` 和 `MATRIXCODE_REMOTE_APP_DIR` 为安全绝对路径。
  - 校验 systemd/Nginx 服务名和健康检查 URL。
  - 校验 SSH 连接超时时间为 1 到 60 秒。
  - 默认输出 `SSH 连接检查：SKIPPED`，只做本地配置预检。
  - 开启连接预检时执行只读 SSH 命令，检查远端 `tar`、`mktemp`、`systemctl` 和应用父目录。
- 新增 `scripts/verify-remote-release-target-test.sh`：
  - 红灯确认旧项目缺少预检脚本。
  - 覆盖空目标、危险应用目录、本地配置预检和 fake SSH 连接预检。
- 发布链路接入：
  - `build-production-server.sh`、`package-production-release.sh` 和发布归档测试纳入脚本。
  - `verify-production-deployment-assets.sh` 纳入脚本存在性、可执行位、语法和 env 示例字段检查。
  - `verify-production-readiness.sh` 从 20 项扩展为 21 项。
  - `generate-production-release-candidate.sh` 在远程目标存在时先执行远程发布目标预检，再执行远程发布 dry-run。

## 验证

- 红灯：`bash scripts/verify-remote-release-target-test.sh` 先因缺少预检脚本失败。
- 目标绿灯：`bash scripts/verify-remote-release-target-test.sh` 通过。
- 关联验证：候选清单测试、发布归档测试、生产部署资产验证和生产就绪 21/21 通过。
- 安全验证：`git diff --check`、真实凭据精确扫描和 staged secret 扫描通过。

## 回溯

- 对齐“真实可上线运行”：远程目标缺失从候选报告中的 `SKIPPED` 风险，升级为独立可执行预检门禁。
- 对齐“自主闭环”：阶段覆盖红灯、实现、绿灯、聚合门禁、候选报告链路、图谱更新和提交推送。
- 对齐“安全边界”：预检默认不连接远端；连接预检也只执行只读命令，不创建目录、不安装、不重启、不 reload Nginx。
