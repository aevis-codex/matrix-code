# MatrixCode TLS 与真实域名入口

## 范围

本手册用于把单机 Nginx 入口从 HTTP 模板升级为 HTTPS 模板。仓库只提供模板和验证脚本，不保存真实域名、证书、私钥或云厂商凭据。

## 前提

- 域名已解析到部署机器公网 IP。
- 机器已安装 Nginx 和 certbot。
- MatrixCode 后端监听 `127.0.0.1:8080`。
- 桌面端 Web 静态产物位于 `/opt/matrixcode/desktop`。

示例域名统一使用 `matrixcode.example.com`，上线前必须替换为真实域名。

## 申请证书

推荐先启用 HTTP 模板，确认 80 端口可访问，再申请证书：

```bash
sudo certbot certonly --nginx -d matrixcode.example.com
```

证书路径应与模板一致：

```text
/etc/letsencrypt/live/matrixcode.example.com/fullchain.pem
/etc/letsencrypt/live/matrixcode.example.com/privkey.pem
```

## 启用 HTTPS 模板

```bash
sudo cp /opt/matrixcode/ops/nginx/matrixcode-https.conf /etc/nginx/conf.d/matrixcode.conf
sudo nginx -t
sudo systemctl reload nginx
```

模板行为：

- 80 端口强制跳转 HTTPS。
- 443 端口启用 TLS 1.2 和 TLS 1.3。
- `/api/` 代理到后端。
- SSE 事件流关闭缓冲。
- `/actuator/health` 和 `/actuator/info` 可用于探活。
- 其他 `/actuator/` 返回 404。
- 静态页面走 `/opt/matrixcode/desktop`。

## 验证

```bash
bash scripts/verify-tls-assets.sh
curl -fsS https://matrixcode.example.com/actuator/health
curl -fsS https://matrixcode.example.com/
```

## 续期

certbot 默认会安装 systemd timer。可检查：

```bash
systemctl list-timers | grep certbot
sudo certbot renew --dry-run
```

证书续期失败应进入告警；告警策略在后续阶段补齐。
