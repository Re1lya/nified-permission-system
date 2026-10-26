# 统一权限管理系统一期设计与实现

> 2026-10-26 当前提交入口：[AsciiDoc 材料](../submission/README.adoc)及[最新 JaCoCo 说明](../submission/06-jacoco.adoc)。本页四份 Word 保留 2026-10-22 快照。

当前修订以 `需求分析基线-V1.0.md` 为准。功能与实现边界见 `requirements.md`，接口见 `api-design.md`，数据库新建脚本见 `database-mysql-phase-one.sql`，旧版升级脚本见 `../migrations/20261022-phase-one-completion.sql`。

前次完成 Java 编译打包、前端类型检查与构建、离线元数据导出和 Word 渲染，记录为 `completion-verification.json`。本次进一步完成真实 MySQL 建库、隔离旧结构升级和云端接口联调，详见 [部署联调说明](cloud-deployment.md) 与 `cloud-verification.json`。上述 2026-10-22 联调未运行项目测试套件。2026-10-26 已运行当前源码完整 Maven 测试（43 通过、1 跳过），见最新报告；未进行性能测试。

`verification.json`、`mysql-verification.json`、`http-verification.json`、Apifox 运行报告和旧截图仅为提交 `01b94f06` 及之前版本的历史证据，不能代表本修订已通过测试。实际通过的本轮联调项目单独列在 `cloud-verification.json`，未列项目不能视为已验收。

当前课程检查使用 [HTTP 门户](http://120.46.213.158/ui/index.html)，无需处理证书。切换说明见 [http-access.md](http-access.md)，增量验证见 `http-access-verification.json`；与旧版 `http-verification.json` 区分。

## 构建

先安装 third-party/clonefactory，再在仓库根目录运行 `mvn -Dmaven.test.skip=true package`；前端先运行 `npm ci` 和 `npm run build`，再打包 Java 以包含最新静态资源。使用 Java 21。

生产配置使用 prod。需先创建全部表或执行一次升级脚本，配置数据库凭证、平台 JWT 密钥和公开 OIDC issuer。HR、OA 使用不同的环境变量密钥 PERMISSION_HR_SECRET 和 PERMISSION_OA_SECRET。默认不注册本地演示客户端；phase-one 配置显式开启本地演示客户端，但这些客户端不是已验收的企业应用。

## 文档再生成

`scripts/ExportSchema.java` 与 `scripts/ExportApi.java` 只读取编译后的类型及注解，不启动服务、不连接数据库。先生成当前结构和路由，再运行 `scripts/build-current-schema.py`、`scripts/build-current-contracts.py`、`scripts/build-phase-one-documents.py`。文档生成不依赖测试产物，也不会覆盖旧版验收报告。

旧库升级前需先排查独立角色的重名岗位映射；升级脚本中的所有预检都应在第一条 DDL 之前完成。迁移仅适用于旧提交 `01b94f06` 的结构，不应盲目用于其他版本。

## 当前设计文件

- [需求分析与实现](01-需求分析文档.docx)
- [界面原型](02-界面原型文档.docx)
- [API 设计](03-API设计文档.docx)
- [数据库设计](04-数据库设计文档.docx)

OpenAPI 含 98 个业务控制器操作，已移除平台直接创建 OA 申请的旧 POST，保留 GET 本人状态查询。2026-10-22 已完成 Apifox 云端同步：98 个接口、25 个数据模型、OpenAPI 1.4.1，并创建和选中 HTTP 检查环境；刷新后核对通过，记录见 `apifox/project.json`。该次 Apifox 同步未执行测试场景。
