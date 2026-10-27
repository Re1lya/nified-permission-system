# 课程检查 HTTP 入口

2026-10-22 按用户要求将检查环境切换为 HTTP，浏览器不再需要配置或信任证书。此为课程演示配置，HTTP 不加密账号、密码和令牌传输；原始需求中的正式 HTTPS 要求不变。四份 Word 和 `cloud-verification.json` 记录的是切换前的设计及 HTTPS 验收，本说明补充当前访问方式。

| 用途 | 当前地址 |
| --- | --- |
| 管理门户 | http://120.46.213.158/ui/index.html |
| OIDC issuer | http://120.46.213.158 |
| OA 契约调用端 | http://120.46.213.158/oa-client/ |
| HR 契约调用端 | http://120.46.213.158/hr-client/ |

管理员账号仍为 `cdwy_admin`，密码未变；本地受限文件 `work/servers/admin-login.txt` 已更新。旧 HTTPS 登录状态不在 HTTP 下复用，请从新地址重新登录。两个调用端仍用于契约验证，没有接入外部 OA/HR 产品。

## 配置与边界

Nginx 对外监听 80；公开 OIDC issuer、应用入口、注册回调和令牌 issuer 全部采用同一 HTTP 来源。内部回环后端仍使用已有 TLS，用户无需处理内部证书。数据库及公网数据库入口未变更。

部署环境显式设置：

```properties
PERMISSION_OIDC_ISSUER=http://120.46.213.158
PERMISSION_APPLICATION_HTTP_DEMO_ORIGIN=http://120.46.213.158
SERVER_SERVLET_SESSION_COOKIE_SECURE=false
SERVER_SERVLET_SESSION_COOKIE_NAME=CDWY_HTTP_SESSION
```

`PERMISSION_APPLICATION_HTTP_DEMO_ORIGIN` 默认为空。非本地应用默认仍要求 HTTPS；配置后，仅该主机及端口允许 HTTP 入口和回调，不支持任意远程 HTTP 地址。回调仍须精确注册，不放宽 state、nonce、PKCE、签名、受众或客户端认证检查。

两个调用端的 `OIDC_ISSUER` 同样设置为 HTTP，保留内部 `OIDC_BACKCHANNEL=https://127.0.0.1:18443` 的 CA 校验。调用端按 issuer 协议选择 Cookie Secure 属性，并为 HTTP 使用独立 Cookie 名；平台也使用独立 Cookie 名，避免之前 HTTPS Cookie 干扰。HttpOnly 和 SameSite=Lax 保留。

## 增量验证

`http-access-verification.json` 记录七组通过结果：门户与管理员登录、OIDC 元数据、HTTP 来源边界、平台 Cookie、两个调用端回调及单点登录、令牌 issuer 和刷新轮换、服务重启后的登录和刷新持久化（相关项目合并为七组记录）。实际部署包摘要也包含在记录中。

仅运行本次协议切换相关的真实 HTTP 流程验证，没有重新运行 Maven 测试套件或全部业务生命周期验收。之前完整业务联调结果仍在 `cloud-verification.json`，不能混同为本次重新执行。

四份 Word 不因环境切换重新定义功能需求。OpenAPI 1.4.2 的默认服务地址已更新为 HTTP；Apifox 云端也已同步到 1.4.2，并配置 `云服务器 HTTP 检查` 环境，前置 URL 为 `http://120.46.213.158`。同步记录见 `apifox/project.json`。

## 恢复原部署

切换前的服务环境配置、调用端环境配置、Nginx 配置及 JAR 路径已保存在服务器 `/var/backups/unified-permission/http-switch-20261022/`，仅 root 可读。回退时须同时恢复 issuer、Cookie、Nginx 监听、调用端配置，并通过管理 API 把两个应用入口和精确回调改回 HTTPS 后重新验收；仅恢复 Nginx 会造成协议不一致。原证书和旧 JAR 保留。
