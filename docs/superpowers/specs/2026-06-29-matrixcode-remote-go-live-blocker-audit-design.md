# MatrixCode 第 166 阶段：远程上线阻塞审计设计

## 目标

在本机发布包 Jar 已通过真实生产启动 smoke 后，确认距离真实远程上线还缺哪些外部条件，并把阻塞点记录成可恢复的上线审计证据。

## 决策

- 不猜测 SSH 用户或密码，不用数据库密码尝试登录系统。
- 只做 TCP 端口探测和 SSH BatchMode 只读登录验证。
- 把远程上线阻塞记录到 `docs/deployment/go-live-readiness-log.md`。
- 不把缺少远程目标写成代码失败；当前项目代码、发布包、生产脚本和本机生产启动链路已经通过。

## 验证

- `nc -vz -G 5 127.0.0.1 22`：TCP 可达。
- `nc -vz -G 5 127.0.0.1 80`：TCP 可达。
- `nc -vz -G 5 127.0.0.1 443`：TCP 可达。
- `ssh -o BatchMode=yes ... root@127.0.0.1 true`：权限拒绝。
- `ssh -o BatchMode=yes ... deploy@127.0.0.1 true`：权限拒绝。
- `ssh -o BatchMode=yes ... matrixcode@127.0.0.1 true`：权限拒绝。
- `ssh -o BatchMode=yes ... aevis@127.0.0.1 true`：权限拒绝。

## 与最初需求对齐

- 对齐“真实可上线运行”：当前代码和发布包已具备上线实物，但真实远程部署还缺目标服务器 SSH 身份。
- 对齐“不要 mock”：本阶段使用真实服务器地址做网络和 SSH 只读验证。
- 对齐“安全边界”：不保存凭据，不执行远端写操作。
