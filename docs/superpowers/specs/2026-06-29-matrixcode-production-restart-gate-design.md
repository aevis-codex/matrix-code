# MatrixCode 生产服务受控重启门禁设计

## 背景

阶段 135 已把发布归档复制到远程服务器前的 dry-run、校验和显式确认补齐，但复制和安装之后仍缺少统一的服务器侧重启门禁。若后续由人工直接执行 `systemctl restart`，容易遗漏健康检查、Nginx reload 策略和确认记录。

## 目标

- 新增服务器本机执行的重启脚本，默认不触发真实重启。
- 真实重启必须设置 `MATRIXCODE_RESTART_CONFIRM=true`。
- dry-run 能输出将要重启的 systemd 服务和健康检查地址。
- 重启后必须执行 HTTP 健康检查。
- Nginx reload 通过 `MATRIXCODE_RELOAD_NGINX=true` 显式开启。
- 发布包、生产资产门禁和聚合 readiness 均校验该脚本。

## 非目标

- 不做远程 SSH 编排。
- 不保存 sudo 凭据。
- 不自动回滚目录或数据库。
- 不接管云厂商、容器平台或多节点发布系统。

## 方案

新增 `scripts/restart-production-services.sh`：

- `MATRIXCODE_SYSTEMD_SERVICE`：默认 `matrixcode`。
- `MATRIXCODE_HEALTH_PROBE_URL`：默认 `http://127.0.0.1:8080/actuator/health`。
- `MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS`：默认 `5`。
- `MATRIXCODE_RESTART_DRY_RUN=true`：只校验配置并输出计划。
- `MATRIXCODE_RESTART_CONFIRM=true`：允许真实执行。
- `MATRIXCODE_RELOAD_NGINX=true`：健康检查通过后 reload Nginx。

安全约束：

- systemd 服务名只允许字母、数字、点、下划线、`@` 和中划线。
- 健康检查地址只允许 `http://` 或 `https://`。
- 超时时间必须为正整数。

## 验证

```bash
bash scripts/restart-production-services-test.sh
bash scripts/package-production-release-test.sh
bash scripts/verify-production-deployment-assets.sh
bash scripts/verify-production-readiness.sh
git diff --check
```

## 回溯

- 与初始目标一致：系统继续走真实上线运行路径，而非 mock 演示。
- 与阶段 133 到 135 对齐：发布链路仍保持「构建、归档、复制、安装、重启」逐步显式确认。
- 与生产安全要求一致：高风险动作默认关闭，必须先 dry-run，再显式确认。
