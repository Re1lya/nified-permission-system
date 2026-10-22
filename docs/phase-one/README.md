# 统一权限管理系统一期材料

本目录是基于 `01-需求分析.md` 和三份市政公司表单的一期推进版本。`docs/first-check` 保留原第一次检查材料，不代表本轮全部功能状态。

## 四份 Word

- [需求分析文档](01-需求分析文档.docx)：范围、角色、业务规则、导入映射、验收条件和需求追踪。
- [界面原型文档](02-界面原型文档.docx)：页面结构、交互规则和实际运行截图。
- [API设计文档](03-API设计文档.docx)：现有接口、鉴权、导入、OIDC、OA/HR 契约。
- [数据库设计文档](04-数据库设计文档.docx)：实际 25 张表的字段、关联、约束、ER 图与 SQL。

Word 由 `scripts/build-phase-one-documents.py` 根据本目录说明、实际数据库元数据和接口映射生成。需求分析中的用例图、领域模型和时序图由 `scripts/build-requirement-diagrams.py` 生成，用例状态改变时需同时更新该脚本和 `requirements.md` 第 12 节。没有虚构成员分工。截图使用虚构演示员工；真实三表与运行凭据不入库。

## 本地启动

1. 设置 `JAVA_HOME` 为 JDK 21，按根 README 安装 CloneFactory。
2. 在 `frontend` 中执行 `npm ci`、`npm run build`。
3. 回到仓库根目录执行 `mvn clean verify`。
4. 执行 `powershell -File scripts/start-phase-one.ps1`，输入首次管理员账号和密码。
5. 浏览器打开 `http://127.0.0.1:18081/ui/index.html`。旧界面 `/` 保留兼容，正式一期演示使用 `/ui/index.html`。

默认只监听本机，使用独立 H2 内存库。停止进程后数据清空；重启后重新导入三表，或运行 `python scripts/make-demo-workbooks.py` 生成三份虚构表用于反复演示。脚本需要 openpyxl，OIDC 客户端需要 cryptography；这些 Python 依赖不属于 Java 服务运行依赖。

## 导入规则

部门、员工、权限清单必须一起预览。预览有效 30 分钟，最多 10 个未过期任务，每份上传小于 10 MB。发现错误不能提交；警告需要勾选确认。提交使用一个数据库事务，任一失败整批回滚；同一三文件指纹重复提交直接返回已导入。

保留 SG 用户名；岗位创建同名角色，不自动授予员工；五类角色列保存为权限模板，管理员可明确追加到岗位角色。应用模板会影响已拥有该岗位角色的员工，应先核对模板覆盖范围。`view` 规范化为查看；空单元格不授权；“全部”仅展开同功能中出现的操作；“查看本人”保持独立编码。

原始表实际验证结果：111 部门、10000 员工、54 岗位、18 系统、115 模块、359 功能、797 操作权限，员工初始授权 0 条。负责人有 1 项无法在所属部门唯一关联；不自动创建管理员。

## OIDC 和 OA/HR 演示

执行 `python scripts/oidc-demo.py`，在 18101、18102 启动两个本地客户端。登录一期门户，在“应用门户”点击建立单点登录会话，然后分别打开两个客户端。采用授权码和 PKCE，验证签名、issuer、audience、nonce、state、有效期。第二个客户端无需再次输入密码。授权同意不等于获得业务权限。

OA/HR 回调默认关闭。演示前在启动后端的终端设置不同的 `PERMISSION_HR_SECRET`、`PERMISSION_OA_SECRET`，每项至少 32 字符；相同密钥通过环境变量提供给 `scripts/send-integration.py`。不要把密钥写进 Git。请求示例及签名规则见 API 文档。

`scripts/verify-phase-one.py` 通过真实 HTTP 验证两个 OIDC 客户端、授权码重放、HR 入调离和 OA 回调。环境变量为 `PERMISSION_ADMIN_USERNAME`、`PERMISSION_ADMIN_PASSWORD`、上述两项集成密钥；结果写入 `output/phase-one-http-results.json`，不包含令牌。它只操作本机新建的 `http_` 前缀演示记录。

## 测试和报告

普通测试：`mvn clean verify`。原始文件验收：先把 `COMPANY_WORKBOOK_DIR` 设置为三份原始 XLSX 所在目录，再运行相同命令；未设置时该项明确跳过。

- JUnit：`target/surefire-reports/`
- JaCoCo：`target/site/jacoco/index.html` 与 `jacoco.xml`
- 可提交的摘要：`verification.json`
- 本地 HTTP：`http-verification.json`
- 原始表导入统计：`actual-import.txt`
- 页面截图：`images/ui-*.png`（20 个需求页面及部门管理员、普通用户视角，虚构演示数据）
- 串联场景：`apifox/phase-one-scenarios.postman_collection.json` 与运行明细 `apifox/phase-one-scenarios-report.json`
- MySQL 结构脚本：`database-mysql-phase-one.sql`，仅用于新建隔离库；MySQL 8.4 实测记录见 `mysql-verification.json`

图由脚本生成：`scripts/build-requirement-diagrams.py`（用例图、领域模型、时序图）、`scripts/build-design-diagrams.py`（完整 ER 图与页面跳转图，需先运行测试导出表结构）、`scripts/build-design-assets.py`（OpenAPI 与分组 ER 图）。OIDC 刷新令牌演示需在启动前设置 `PERMISSION_OIDC_CLIENT_SECRET`（至少 32 字符），并以相同值运行 `scripts/oidc-demo.py`。

## 按课堂要求检查设计

1. 需求 Spec：requirements.md，每项有编号、输入、行为、验收条件及追踪矩阵。
2. 可运行界面：frontend/，Vue 3 + TypeScript + Tailwind；Word 包含真实页面截图。
3. Apifox：导入 apifox/openapi.json，按 apifox/checks.md 建立检查场景。
4. 数据库：database-er.mmd 为可编辑 ER 源码，images/er-*.png 为分组关系图，database-mysql-phase-one.sql 为结构 SQL。

设计关联规则：Spec 编号 → 页面操作 → OpenAPI 接口 → ER 实体与 SQL 表 → 检查断言。材料介绍当前一期完成的功能；本地演示和测试采用隔离 H2。


Apifox 项目已在 CDWY 团队创建：https://app.apifox.com/project/8955177 。实际导入 68 个接口、20 个数据模型、1 个本地环境、3 个鉴权组件。导入截图：images/apifox-import.png。


已保存 Apifox 场景 SESSION-01（编号 8875512）。本地 CLI 场景文件为 apifox/session-01.apifox-cli.json，含 401 状态断言及错误响应结构校验；本次运行 1 个请求、1 条断言通过。报告为 apifox/session-01-report.html 和 .json。复现命令：apifox run docs/phase-one/apifox/session-01.apifox-cli.json -n 1 -r cli,html,json。网页跨域调试时使用 Apifox 扩展或桌面端；本机 CLI 可直接访问 18081。
