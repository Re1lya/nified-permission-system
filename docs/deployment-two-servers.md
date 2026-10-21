> 此文为部署参考，本次第一次检查没有实际部署或验证 MySQL/多实例。当前本地启动请用 [检查材料](first-check/local-run.md)。新增管理员字段需执行 20261021 迁移；首次管理员只能由一个实例初始化，不能并发引导。默认只监听 127.0.0.1。

# 两台服务器部署说明

本说明不包含真实 IP、密码或 JWT 密钥。部署时把示例值替换为实际值，并通过服务器环境变量注入，不要写入 Git 仓库。

## 1. 推荐拓扑

最小可用拓扑是一台应用服务器加一台数据库服务器：

```text
用户 / OA / 业务系统
          │
          ▼
服务器 1：Spring Boot（8080）
          │ 内网 MySQL 连接
          ▼
服务器 2：MySQL（3306）
```

如果两台服务器都要运行应用，则还需要一个两台应用服务器都能访问的 MySQL 实例或数据库集群；两台应用服务器必须使用完全相同的 `PERMISSION_JWT_SECRET`。登录会话统一保存在 `sys_login_session` 中，注销和账号变更不依赖单机内存。当前集成测试使用 H2，真实 MySQL 多实例验证仍需在部署时完成。

## 2. 服务器 2：初始化 MySQL

新数据库在数据库服务器执行 [database-mysql.sql](database-mysql.sql)。如果数据库已有旧版六张表，使用 [migrations/20261019-login-sessions.sql](migrations/20261019-login-sessions.sql) 新增会话表。随后统一升级所有应用实例，用户需重新登录。

再创建一个只允许应用服务器访问的账号，例如：

```sql
CREATE USER 'unified_permission'@'应用服务器内网IP'
    IDENTIFIED BY '请替换为强密码';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
    ON unified_permission.*
    TO 'unified_permission'@'应用服务器内网IP';
FLUSH PRIVILEGES;
```

MySQL 防火墙只放行应用服务器到 `3306` 的连接，禁止把 `3306` 暴露到公网。数据库账号密码只保存于服务器的环境变量或密钥管理工具。

## 3. 服务器 1：准备应用包

在开发机的项目目录执行：

```powershell
$env:JAVA_HOME = 'C:\Users\姚景云\.jdks\corretto-21.0.12.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn "-Dmaven.repo.local=D:\CodexData\cache\m2" clean package
```

把生成的 `target/nified-permission-system-0.0.1-SNAPSHOT.jar` 上传到服务器 1，例如 `/opt/unified-permission-system/app.jar`。服务器 1 安装 JDK 21，并确认：

```bash
java -version
```

## 4. 生产环境变量

服务器 1 创建只允许 root 读取的环境文件 `/etc/unified-permission.env`：

```dotenv
SERVER_PORT=8080
DB_URL=jdbc:mysql://数据库服务器内网IP:3306/unified_permission?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DB_USERNAME=unified_permission
DB_PASSWORD=请替换为数据库密码
PERMISSION_JWT_SECRET=请替换为随机生成且至少32字节的密钥
PERMISSION_JWT_EXPIRATION_SECONDS=7200
```

`DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 和 `PERMISSION_JWT_SECRET` 会被 `application-prod.yml` 读取。两台应用服务器部署时，数据库地址和 JWT 密钥必须保持一致。

## 5. 使用 systemd 启动

服务器 1 创建 `/etc/systemd/system/unified-permission.service`：

```ini
[Unit]
Description=Unified Permission System
After=network-online.target
Wants=network-online.target

[Service]
User=unified-permission
WorkingDirectory=/opt/unified-permission-system
EnvironmentFile=/etc/unified-permission.env
ExecStart=/usr/bin/java -jar /opt/unified-permission-system/app.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

创建运行用户并启动：

```bash
useradd --system --home /opt/unified-permission-system --shell /usr/sbin/nologin unified-permission
chown -R unified-permission:unified-permission /opt/unified-permission-system
chmod 600 /etc/unified-permission.env
systemctl daemon-reload
systemctl enable --now unified-permission
systemctl status unified-permission
```

查看日志：

```bash
journalctl -u unified-permission -f
```

## 6. 端口和检查顺序

- `22`：仅允许管理员来源地址远程登录。
- `8080`：只允许反向代理或内网业务系统访问；有 Nginx 时不直接暴露公网。
- `3306`：只允许应用服务器访问。

应用启动后先检查：

```bash
curl http://127.0.0.1:8080/hello
```

应返回：

```json
{"message":"unified-permission-system"}
```

然后从 Postman 导入 [unified-permission-system.postman_collection.json](postman/unified-permission-system.postman_collection.json)，把 `baseUrl` 改成应用服务器地址，按集合顺序验收登录和权限回收。

## 7. 生产上线前检查

- 两台应用实例使用同一个 JWT 密钥。
- 应用服务器能够连通数据库服务器 `3306`。
- MySQL 已执行建表脚本，`ddl-auto=validate` 启动无 schema 错误。
- 没有把 `.env`、数据库密码或 JWT 密钥提交到 Git。
- 已通过健康检查和 Postman 验收流程。
- 多实例使用相同数据库，验证在实例 A 注销后实例 B 的后续请求返回 `401`。
- 当前管理 API 已校验单一全局管理员；部门/租户范围没有实现，真实业务接入另行设计和验收。

会话过期后即无法通过校验，但记录暂不自动清理。可配置数据库维护任务定期执行 `DELETE FROM sys_login_session WHERE expires_at <= UTC_TIMESTAMP(6)`，会话表的到期时间已有索引。
