# 功能权限目录 MySQL 兼容性修复

2026-10-22，线上 `/api/company/catalog` 返回 500。服务器日志定位到查询中的 `s.name system`：`SYSTEM` 是 MySQL 保留字，未引用的别名导致 SQL 语法错误。

改为内部别名 `system_name`，查询后映射回 JSON 字段 `system`，保持前端和接口契约不变，无需数据库迁移。

## 验证

- `mvn -Dmaven.test.skip=true package` 构建成功；未运行项目测试套件或 JaCoCo。
- 更新应用服务器后，通过公开 HTTP 入口、管理员登录和真实 MySQL 验证：首页、第二页、特殊字符搜索、超出末页请求均返回 200；未登录返回 401，负数页码返回 400。
- 现有功能目录共 0 条，因此本次验证覆盖空目录查询及 SQL 可执行性，尚未覆盖含真实目录条目的展示和搜索命中。未向生产库插入验证数据。
- 详细结果见 `catalog-verification.json`。

部署 JAR SHA-256：`a8b765e2530af1fdb6daf0a185f0769fc22b343306043efc16107b101680702e`。
