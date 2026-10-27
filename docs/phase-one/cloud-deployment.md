# 云端部署与接口联调记录

> **当前检查入口已切换为 HTTP**：http://120.46.213.158/ui/index.html，无需浏览器配置证书。以下 HTTPS 记录保留为之前的部署与验收历史；当前地址、配置和增量验收见 [HTTP 检查入口说明](http-access.md)。

2026 年 10 月 22 日，应用已部署到 CDWY3，使用 CDWY2 的真实 MySQL 9.2。此次执行用户授权的数据库迁移验证和接口流程联调，没有运行 Maven 单元或集成测试套件，没有生成新的 JaCoCo 报告。OA/HR 产品尚不存在，两个接入端是明确标注用途的接口契约调用端。

## 访问与进程

| 项目 | 地址或配置 |
| --- | --- |
| 管理门户 | https://120.46.213.158/ui/index.html |
| OIDC issuer | https://120.46.213.158 |
| OA 接口调用端 | https://120.46.213.158/oa-client/ |
| HR 接口调用端 | https://120.46.213.158/hr-client/ |
| 应用运行 | CDWY3，Java 21，systemd unified-permission.service |
| 反向代理 | CDWY3，Nginx，443 TLS；后端仅监听 127.0.0.1:18443 |
| 调用端运行 | unified-permission-clients.service，回环 18101 与 18102 |
| 数据库 | CDWY2，应用通过私网 172.31.8.46:3306 访问 unified_permission |

当前是课程环境的自签名证书，需要显式信任对应证书。联调使用固定 CA 文件校验证书和主机名，没有关闭 TLS 校验；浏览器可能显示证书信任提示。证书有效期九十天。未提供域名及公开 CA 证书，不宣称已完成公开可信证书配置。

服务设置为开机启动，JAR 位于 `/opt/unified-permission/releases/`，`app.jar` 指向当前版本。配置文件 `/etc/unified-permission.env` 和 `/etc/unified-permission-clients.env` 仅 root 可读；密钥及管理员密码不提交 Git。管理员账号 `cdwy_admin`，密码保存在操作者本地受限权限的 `work/servers/runtime-private.json`，不使用代码中的默认密码。

数据库公网入口、原有账号及其他课程服务按要求保留。CDWY1、CDWY4 未变更。此次没有部署多实例、数据库复制或负载均衡。

## 数据库新建与升级验证

服务器原有数据库属于其他课程项目，没有发现旧版 RBAC 业务库。先备份这些库，然后新建 `unified_permission` 的三十八张表供当前后端使用。不能把这一步描述为迁移了已有 RBAC 业务数据。

另建隔离库 `rbac_migration_20261022`，加载提交 `01b94f06` 的二十五表结构及合成样例，执行 `docs/migrations/20261022-phase-one-completion.sql`。旧快照四条索引中的 H2 专用 `NULLS FIRST` 在准备 MySQL 旧库样例时移除；正式升级脚本按仓库版本执行。验证结果如下：

- 升级后共三十八张表，prod Hibernate 结构校验和临时实例启动成功。
- 两个原有账号的登录名及凭证摘要保留，用户角色关联保留。
- 既有权限版本 5 保留；首次管理员标记保留。
- 无岗位的独立角色补建对应岗位，岗位角色数量一致。

备份位于 CDWY2 的 `/var/backups/unified-permission/20261022-before/`，其中 `existing-course-databases.sql` 是变更前的其他课程库备份，`rbac-old-schema-with-fixtures.sql` 是隔离旧结构样例备份。目录及文件仅 root 可访问。没有执行备份恢复演练；MySQL DDL 隐式提交，遇到半途失败应分析实际结构或恢复隔离备份，不能盲目重跑升级脚本。

## 实际接口验证范围

逐项结果、部署 JAR 摘要与迁移核对值见 `cloud-verification.json`。验证使用专门创建的合成人员、部门、岗位和应用记录，不是实际员工数据。离职链路完成后的验收员工保持停用，历史授权及审计证据保留。

覆盖真实 HTTPS 登录和 JSON v2 响应、HR 全量与增量事务、外部编号映射、拼音账号、初始密码一次发放、强制改密、签名校验、幂等与冲突、失败队列和对账；覆盖 OA 登记、查询、批准、驳回和取消，平台旧申请 POST 已删除，GET 状态查询保留。

覆盖权限复核保留和回收、部门范围、引用删除保护、外部人员有效期，以及 OIDC 授权码、PKCE、同意、state、nonce、RSA 签名、受众隔离、userinfo、两调用端真实回调和免登、刷新轮换、重放拒绝、服务重启持久化、调岗撤权、强制下线和离职。超过十二个月的合成审计事件由定时任务转入归档，在线副本移除，内容保留。

本轮发现并修复 MySQL DATETIME 映射、外部人员查询缺少账号表关联、OAuth2 JDBC 元数据对 Long 权限版本的序列化白名单问题。没有放宽所有类型的反序列化权限，仅允许具体的 Long 类型。

## 重复执行与交付边界

`verify-cloud-deployment.py` 读取私有配置和 CA 文件，在真实系统中创建合成数据，并在服务重启前暂停；操作者重启后继续验证。报告不写入密码、令牌、授权码或私钥。`oidc-cloud-client.py` 运行两个契约调用端，采用 BASIC 客户端认证、S256 PKCE 和严格回调验证，不记录回调查询串。重复运行会增加合成记录，不能当作生产负载工具。

性能、容量、多实例切换、备份恢复、逐页浏览器交互和外部 OA/HR 产品接入均不在本轮通过声明中。此处 HTTPS 联调完成时，Apifox 云端同步曾按用户要求暂缓。后续 HTTP 切换及本次同步已完成，云端当前为 OpenAPI 1.4.2、98 个接口和 HTTP 检查环境；实际同步结果见 `apifox/project.json`。旧 `cloud-verification.json` 保留当时的验收状态。
