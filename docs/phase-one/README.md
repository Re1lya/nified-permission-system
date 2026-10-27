# 一期设计与阶段资料

本目录保存需求基线、2026-10-22 的 Word 设计快照、接口契约、数据库结构及阶段验证记录。当前检查文档、测试报告和部署状态统一见 [AsciiDoc 材料目录](../submission/README.adoc)。

## 设计文件

| 文件 | 内容与版本 |
| --- | --- |
| [需求分析基线 V1.0](需求分析基线-V1.0.md) | 原始需求、业务规则及用例 |
| [需求分析与实现说明](01-需求分析文档.docx) | 2026-10-22 Word 设计快照 |
| [界面原型](02-界面原型文档.docx) | 页面范围及交互设计 |
| [API 设计](03-API设计文档.docx) | 业务接口与集成契约 |
| [数据库设计](04-数据库设计文档.docx) | 数据模型、字段和约束 |
| [需求说明源文件](requirements.md) | AsciiDoc 生成使用的阶段设计资料 |
| [API 说明源文件](api-design.md) | AsciiDoc 生成使用的接口说明 |

## 接口与数据库

- [OpenAPI 1.4.2](apifox/openapi.json)：98 个业务控制器操作、25 个数据模型；[Apifox 同步记录](apifox/project.json)记录 2026-10-28 的同步状态。
- [MySQL 完整建表脚本](database-mysql-phase-one.sql)：适用于空数据库，共 38 张表。
- [数据库升级脚本](../migrations/20261022-phase-one-completion.sql)：适用于提交 `01b94f06` 的二十五表结构。执行前需要检查前提条件、停止写入并备份。
- [HTTP 访问说明](http-access.md)：课程演示入口及相关配置。

## 验证记录索引

| 日期或版本 | 记录 | 验证范围 |
| --- | --- | --- |
| `01b94f06` 及更早版本 | `verification.json`、`mysql-verification.json`、`http-verification.json` | 早期一期实现的验证 |
| 2026-10-22 | [编译与文档记录](completion-verification.json) | 后端编译、前端构建及文档导出 |
| 2026-10-22 | [云端联调记录](cloud-verification.json) | MySQL 建库、隔离旧结构升级及接口契约联调 |
| 2026-10-22 | [HTTP 切换记录](http-access-verification.json) | 入口、Cookie、OIDC 回调和会话持久化 |
| 2026-10-22 | [目录修复记录](catalog-verification.json) | 功能权限目录的 MySQL 查询与响应 |
| 2026-10-26 | [JaCoCo 与测试说明](../submission/06-jacoco.adoc) | 当前源码自动测试：43 通过、1 跳过 |
| 2026-10-26 | [部署与验证记录](../submission/07-deployment.adoc) | 修复版本部署及 48 项在线检查 |

各记录按其日期、提交号和验证范围解释。OA/HR 联调采用接口契约演示端。

## 文档维护

`ExportSchema.java` 与 `ExportApi.java` 从编译后的实体及控制器提取结构和接口信息；相关生成脚本位于 `scripts`。当前 AsciiDoc 文档由 `scripts/build-submission-docs.py` 生成，测试证据由 `scripts/export-evidence.py` 在完整测试成功后导出。

Word 文件作为阶段快照保留。更新文档时应同步修改生成脚本或源文件，并检查链接、表格及渲染结果。

## ER 图版本与重新生成

2026-10-28 已统一为 38 张表、26 个物理外键。查看 [完整矢量图](images/er-full.svg) 或 [PNG](images/er-full.png)，关系基数说明见 [当前数据库设计](../submission/03-database-design.adoc)。分域图重复显示域外父表，不能累加方框数量。四份 Word 保留 10 月 22 日的历史快照，当前检查以 AsciiDoc 为准。

从仓库根目录运行 `python3 scripts/build-er-assets.py`，再运行 `node scripts/render-er.cjs` 渲染。渲染依赖 `@viz-js/viz` 和 `@resvg/resvg-js`，可安装在独立工具目录后通过 `NODE_PATH` 指定其 node_modules。历史 H2 绘图脚本已停用，防止覆盖新图。

生成顺序：

```sh
python3 scripts/build-er-assets.py
npm install --prefix ../diagram-tools @viz-js/viz@3.31.0 @resvg/resvg-js@2.6.2 --no-audit --no-fund
NODE_PATH=../diagram-tools/node_modules node scripts/render-er.cjs
python3 scripts/check-submission-consistency.py
```

`api-routes.json` 是离线反射导出的原始路由元数据，不是可直接调用的契约。`build-current-contracts.py` 将导入接口的 MultipartFile 参数归入请求体；调用及 Apifox 导入以 `apifox/openapi.json` 为准。
