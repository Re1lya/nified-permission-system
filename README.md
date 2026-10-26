# 统一权限管理系统

一期实现包含 RBAC0、统一认证与 OIDC、OA 签名审批接口、HR 组织与员工同步、三表导入、部门管理范围及 Vue 管理门户。需求基线见 [DOC-01 V1.0](docs/phase-one/需求分析基线-V1.0.md)。

## 当前检查材料

**[AsciiDoc 材料总入口](docs/submission/README.adoc)**

- [需求与实现说明](docs/submission/01-requirements.adoc)
- [详细设计](docs/submission/02-detailed-design.adoc)
- [数据库设计：38 张表](docs/submission/03-database-design.adoc)
- [API 设计：98 个业务控制器操作](docs/submission/04-api-design.adoc)
- [界面设计](docs/submission/05-ui-design.adoc)
- [JaCoCo 与测试说明](docs/submission/06-jacoco.adoc)

2026-10-26 执行完整 `mvn -B clean verify`：44 项，43 通过，1 项因未提供外部 Excel 数据跳过，0 失败、0 错误。行覆盖率 **72.78%**，分支覆盖率 **36.92%**。

[完整 JaCoCo 报告](docs/submission/evidence/jacoco/index.html)及[对应提交与源码摘要](docs/submission/evidence/test-summary.json)已提交。若仓库网页只显示 HTML 源码，下载或克隆仓库后在浏览器打开该文件。本次是本地 H2 自动测试，不代表重做了云端迁移、压力测试或外部 OA/HR 产品验收。

## 构建与测试

使用 JDK 21、Maven 3.9+。仓库已包含构建后的界面；修改前端时需先在 `frontend` 运行 `npm ci`、`npm run build`，再打包后端。

```sh
mvn -B -f third-party/clonefactory/pom.xml -Dmaven.test.skip=true install
mvn -B clean verify
# 报告：target/site/jacoco/index.html
python3 scripts/export-evidence.py
python3 scripts/build-submission-docs.py
```

Windows 的 Python 命令若为 `python`，替换最后两行。根项目测试命令不能添加跳过测试参数；导出脚本自身不运行测试。

本地一期启动见 [启动脚本](scripts/start-phase-one.ps1)，地址为 `http://127.0.0.1:18081/ui/index.html`；首次管理员凭据由操作者输入。默认 H2 仅用于本地，停止后数据清空。生产配置及数据库建表/迁移见 [一期说明](docs/phase-one/README.md)。

## 部署与材料版本

现有课程检查入口：[HTTP 门户](http://120.46.213.158/ui/index.html)，配置说明见 [HTTP 访问说明](docs/phase-one/http-access.md)。2026-10-22 云端建库、隔离旧结构升级和接口联调见 [部署联调记录](docs/phase-one/cloud-deployment.md)。暂没有外部 OA/HR 产品，之前使用接口契约调用端验证。本次测试修复未在此轮重新部署到云端。

- 当前提交以 `docs/submission/` 的 `.adoc` 和报告为准。
- [四份 Word](docs/phase-one/README.md)保留为 2026-10-22 的设计快照。
- [早期首次检查材料](docs/first-check/README.md)保留 2026-10-21 七表版本及其测试，不能代表当前版本。
- [OpenAPI](docs/phase-one/apifox/openapi.json)、[全量 MySQL SQL](docs/phase-one/database-mysql-phase-one.sql)、[旧版迁移 SQL](docs/migrations/20261022-phase-one-completion.sql)。迁移脚本仅用于注明的旧结构。

答辩复习 PDF 为个人复习资料，不属于本次仓库提交材料。真实账号密码、数据库凭据和签名密钥不写入公开材料。
