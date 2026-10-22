# 统一权限管理系统一期设计与实现

当前修订以 `需求分析基线-V1.0.md` 为准。功能与实现边界见 `requirements.md`，接口见 `api-design.md`，数据库新建脚本见 `database-mysql-phase-one.sql`，旧版升级脚本见 `../migrations/20261022-phase-one-completion.sql`。

本次执行 Java 编译打包、前端类型检查及构建、离线接口与数据库结构导出、Word 渲染检查；没有执行项目测试、接口验收、数据库迁移或服务器部署。`completion-verification.json` 记录本次实际检查范围。

`verification.json`、`mysql-verification.json`、`http-verification.json`、Apifox 运行报告和旧截图仅为提交 `01b94f06` 及之前版本的历史证据，不能代表本修订已通过测试。新增需求的运行验收状态统一为待执行。

## 构建

先安装 third-party/clonefactory，再在仓库根目录运行 `mvn -Dmaven.test.skip=true package`；前端先运行 `npm ci` 和 `npm run build`，再打包 Java 以包含最新静态资源。使用 Java 21。

生产配置使用 prod。需先创建全部表或执行一次升级脚本，配置数据库凭证、平台 JWT 密钥和公开 OIDC issuer。HR、OA 使用不同的环境变量密钥 PERMISSION_HR_SECRET 和 PERMISSION_OA_SECRET。默认不注册本地演示客户端；phase-one 配置显式开启本地演示客户端，但这些客户端不是已验收的企业应用。

## 文档再生成

`scripts/ExportSchema.java` 与 `scripts/ExportApi.java` 只读取编译后的类型及注解，不启动服务、不连接数据库。先生成当前结构和路由，再运行 `scripts/build-current-schema.py`、`scripts/build-current-contracts.py`、`scripts/build-phase-one-documents.py`。文档生成不依赖测试产物，也不会覆盖旧版验收报告。

旧库升级前需先排查独立角色的重名岗位映射；升级脚本中的所有预检都应在第一条 DDL 之前完成。迁移仅适用于旧提交 `01b94f06` 的结构，不应盲目用于其他版本。
