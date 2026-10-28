# 统一权限管理系统

面向对象分析与设计课程项目。系统以 RBAC0 为核心，为多个业务应用提供统一身份认证、角色授权、组织管理和审计功能。

**小组成员：温家正、姚景云、杜奕含、陈浩贤**

## 项目概况

| 项目 | 说明 |
| --- | --- |
| 需求基线 | [RBAC 权限管理系统需求分析（DOC-01 V1.0）](docs/phase-one/需求分析基线-V1.0.md) |
| 后端 | Java 21、Spring Boot 4.0.8、Spring Data JPA、Spring Security、CloneFactory |
| 前端 | Vue 3、TypeScript、Tailwind CSS |
| 数据存储 | 部署环境使用 MySQL；自动测试使用 H2 |
| 认证与授权 | 平台 JWT、OIDC 授权码与 PKCE、RBAC0、部门管理范围 |
| 在线系统 | [统一权限管理门户](http://120.46.213.158/ui/index.html) |
| 最近部署 | 2026-10-27，详见[部署与验证记录](docs/submission/07-deployment.adoc) |

## 主要功能

- **统一认证**：账号或唯一手机号登录、密码管理、会话管理、令牌刷新与撤销。
- **权限管理**：用户—角色、角色—权限两组多对多关系；有效权限取启用角色的有效授权并集。
- **组织管理**：部门、岗位、员工及外部人员维护，部门管理员按绑定范围执行管理操作。
- **数据导入与同步**：部门、员工、功能权限清单导入；HR 签名接口、同步异常记录与对账。
- **应用接入**：OIDC 客户端配置、接入验收和应用门户；OA 申请登记与审批结果回调。
- **审计与复核**：登录及操作日志、权限复核、审计查询与归档。

一期采用 RBAC0，不包含角色继承和互斥。OA、HR 联调使用接口契约演示端，尚未接入外部业务产品；报销页面为权限校验示例。

## 代码阅读

[代码阅读与答辩索引](docs/code-reading-guide.adoc)串联架构职责、API 资源、对象关系、事务边界和关键调用链。各主要 Java 包的 `package-info.java` 及核心类中的中文注释提供就地阅读说明。

## 设计文档与检查材料

[材料总目录（AsciiDoc）](docs/submission/README.adoc)

| 材料 | 主要内容 |
| --- | --- |
| [需求与实现说明](docs/submission/01-requirements.adoc) | 需求范围、业务规则、用例与实现对应关系 |
| [详细设计](docs/submission/02-detailed-design.adoc) | 模块职责、对象关系、关键交互、事务及设计依据 |
| [数据库设计](docs/submission/03-database-design.adoc) | 38 张表的字段、关联、约束、索引及完整 DDL |
| [API 设计](docs/submission/04-api-design.adoc) | 98 个业务控制器操作、认证、签名和响应格式 |
| [界面设计](docs/submission/05-ui-design.adoc) | 页面结构、身份范围、交互流程及设计示意 |
| [测试与 JaCoCo 报告说明](docs/submission/06-jacoco.adoc) | 测试环境、结果、覆盖率和复现方法 |
| [部署与验证记录](docs/submission/07-deployment.adoc) | 部署版本、运行状态及在线接口检查结果 |

配套文件：[完整 ER 图](docs/phase-one/images/er-full.svg)、[OpenAPI 契约](docs/phase-one/apifox/openapi.json)、[MySQL 建表脚本](docs/phase-one/database-mysql-phase-one.sql)、[数据库升级脚本](docs/migrations/20261022-phase-one-completion.sql)。

本 GitHub 仓库保留中文代码注释与阅读索引；2026-10-28 同步 ER、API 文档及已部署的导入、弹窗修复。

## 测试结果

2026-10-28 在 Java 21、Maven 3.9.16、H2 隔离构建环境执行 `mvn -B clean verify`，并通过 `COMPANY_WORKBOOK_DIR` 启用真实三表测试，结果如下。

| 测试总数 | 通过 | 跳过 | 失败 | 错误 | 行覆盖率 | 分支覆盖率 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 189 | 189 | 0 | 0 | 0 | 93.77% | 85.47% |

本次启用真实三表测试，无跳过项；真实 Excel 验证了 10000 名员工、111 个部门、359 项功能。合成工作簿、权限边界、事务回滚、HR/OA 幂等及 OIDC 协议链路测试均已执行。覆盖率统计范围及源码摘要见[测试汇总](docs/submission/evidence/test-summary.json)。

JaCoCo 提供 [HTML](docs/submission/evidence/jacoco/index.html)、[XML](docs/submission/evidence/jacoco/jacoco.xml) 和 [CSV](docs/submission/evidence/jacoco/jacoco.csv) 文件。完整交互报告可在下载或克隆仓库后，通过浏览器打开 `docs/submission/evidence/jacoco/index.html` 查看。

2026-10-27 对导入优化和弹窗修复后的代码完成完整测试，并将同一测试构建更新到服务器，核对编译类与打包资源一致；部署及在线读取检查见[当前部署记录](docs/submission/07-deployment.adoc)。2026-10-26 的 48 项在线检查作为历史记录保留，涵盖管理接口、会话延续及 OA/HR 演示端 OIDC 回调。性能、容量、多实例切换和外部 OA/HR 产品接入不在上述验证范围内。

## 构建与运行

### 环境要求

- JDK 21、Maven 3.9+。
- 修改并重新构建前端时，需要 Node.js 及 npm；前端依赖版本由 `frontend/package-lock.json` 固定。
- 导出报告及生成 AsciiDoc 文档时，需要 Python 3。

### 构建与测试

在仓库根目录执行：

```sh
mvn -B -f third-party/clonefactory/pom.xml -Dmaven.test.skip=true install
mvn -B clean verify
```

构建产物为 `target/nified-permission-system-0.0.1-SNAPSHOT.jar`，本次运行生成的报告位于 `target/site/jacoco/index.html`。第一条命令安装项目内置的 CloneFactory 依赖；第二条命令执行后端完整测试并生成 JaCoCo 报告。

仓库包含已构建的前端资源。修改前端后，先在 `frontend` 目录执行 `npm ci` 和 `npm run build`，再构建后端。

### 本地启动

配置 `PERMISSION_BOOTSTRAP_USERNAME` 和 `PERMISSION_BOOTSTRAP_PASSWORD` 作为首次管理员凭据后，执行：

```sh
java -jar target/nified-permission-system-0.0.1-SNAPSHOT.jar --spring.profiles.active=phase-one --server.port=18081
```

本地访问地址为 `http://127.0.0.1:18081/ui/index.html`。Windows 可使用[启动脚本](scripts/start-phase-one.ps1)按提示输入凭据。本地配置使用 H2 内存数据库，进程停止后数据清空；服务器使用 `prod` 配置和持久化 MySQL 数据库。

### 报告与文档导出

完整测试成功后，可执行：

```sh
python3 scripts/export-evidence.py
python3 scripts/build-submission-docs.py
```

Windows 的 Python 命令若为 `python`，相应替换命令名。报告导出脚本读取已有测试产物，不重复执行测试。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `src/main` | 后端实现、配置及静态资源 |
| `src/test` | 自动测试 |
| `frontend` | 管理界面源码 |
| `docs/submission` | 当前设计文档、测试报告和部署记录 |
| `docs/phase-one` | 需求基线、Word 设计快照、接口契约和阶段验证记录 |
| `docs/migrations` | 指定旧版本的数据库升级脚本 |
| `docs/first-check` | 2026-10-21 初版设计及测试归档 |
| `scripts` | 构建辅助、文档生成、报告导出及联调脚本 |
| `third-party/clonefactory` | 项目内置对象映射依赖 |

当前设计与检查材料以 `docs/submission` 为入口。历史资料保留原日期及适用版本，便于追溯设计演进。
