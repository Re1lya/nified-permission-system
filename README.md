> 当前课程检查入口：[HTTP 管理门户](http://120.46.213.158/ui/index.html)。无需证书，配置与验证见 [HTTP 说明](docs/phase-one/http-access.md)。

# 统一权限管理系统

当前一期版本以 [原始需求及实现说明](docs/phase-one/README.md) 为准，包含 RBAC0、OIDC、OA 签名审批接口、HR 组织和员工同步、三表导入及 Vue 管理门户。

当前设计材料为 [四份 Word 与接口数据库设计](docs/phase-one/README.md)。2026 年 10 月 22 日云端部署和数据库升级验证记录见 [部署联调说明](docs/phase-one/cloud-deployment.md)。外部 OA/HR 暂不存在，本轮使用接口契约调用端；不宣称完成企业产品接入。

## 早期第一次检查基线

以下为早期版本的历史说明和测试记录，不代表当前一期范围或当前验证结果。

第一次检查的本地可运行版本：Spring Boot 4 / Java 21 / JPA / CloneFactory，原生中文管理网页与后端同源发布。管理 API 需要管理员身份，普通用户只能访问自己的身份/有效权限和受保护业务示例。

本次范围仅为 RBAC0：用户通过多个角色获得权限并集，不包含继承、互斥、OA 审批、HR 同步或 Excel 导入。报销列表是固定演示数据。

## 第一次检查材料

从 [检查材料入口](docs/first-check/README.md) 阅读需求对应表、详细设计、页面截图、演示步骤和测试结果。现有部署说明只是参考，**本次没有连接 MySQL、部署服务器或验证多实例**。

## 本地启动

在项目根目录执行，先确认 `java -version` 与 `mvn -version` 使用 JDK 21。Maven 3.9、Python 3（只用于数据准备）即可；无需 Node 或前端包管理器。

```powershell
# JAVA_HOME 改成自己机器的 JDK 21 目录
$env:JAVA_HOME = 'C:\Users\YOLO\.jdks\ms-21.0.12.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -f third-party/clonefactory/pom.xml clean install
mvn clean verify
.\scripts\start-demo.ps1
```

按提示输入自己的首次管理员用户名和密码。浏览器访问 <http://127.0.0.1:18080>。默认管理员密码不写在代码里；首次初始化没有公开 API。另开一个终端，在同一目录执行：

```powershell
python scripts/demo-data.py --verify
```

输入刚才的管理员凭据，再为两个演示用户设置临时密码。脚本准备 `demo_alice`（两个角色）和 `demo_bob`（无角色），验证后恢复演示基线。重复运行会重置这两个账号的密码、角色与会话，不应对自己的业务账号使用这些保留名称。

停止应用后 H2 内存数据清空。重启启动脚本并再次运行数据脚本即可恢复，不需要手工重复点配置。详见 [启动与初始化](docs/first-check/local-run.md)。

## 验证

2026-10-21，本机 JDK 21 + H2：原有 13 项测试通过；补充管理边界后 20 项测试通过，14 项真实 HTTP 验证通过。浏览器操作与截图见检查材料。最终覆盖率以 [测试报告](docs/first-check/testing.md) 和真实 JaCoCo 文件为准。

```powershell
mvn clean verify
# 打开 target/site/jacoco/index.html；JUnit 明细在 target/surefire-reports
```

交付的报告快照在 `docs/first-check/evidence/`。测试不依赖正在运行的演示服务。修改代码后重新运行，旧报告快照不会自动更新。

## 接口与数据库

- [API 文档](docs/rbac-api.md)：所有 `/api/users`、`roles`、`systems`、`permissions` 的读写均要求管理员 JWT。
- [Postman 集合](docs/postman/unified-permission-system.postman_collection.json)：在只初始化管理员的全新 demo 数据库顺序执行；本地填写变量，不导出真实凭据。
- [数据库与功能详细设计](docs/first-check/design.md)：七张表、约束、授权计算、初始化边界与功能调用。
- [MySQL 建表](docs/database-mysql.sql)；已有数据库另需 [管理员字段迁移](docs/migrations/20261021-administrator.sql)。SQL 尚未在真实 MySQL 验收。
- [CloneFactory](docs/clonefactory.md)：必须先安装内置依赖，生成代码不能手工修改。

管理员标记独立于业务角色，仅由服务启动初始化写入。不存在通过创建一个同名角色获得管理员权限的行为；管理员也不会自动拥有 `expense:list`。登录/注销采用 JWT + 数据库会话，角色回收影响后续请求，注销和账号停用使旧 JWT 失效。
# 一期推进版本入口

新增的 Vue 管理界面、三表导入、部门范围、OIDC 与 OA/HR 本地演示及四份 Word，见 [一期材料](docs/phase-one/README.md)。启动地址为 `http://127.0.0.1:18081/ui/index.html`。本地一期采用 Vue3 + TypeScript + Tailwind，配套需求 Spec、Apifox 接口契约、ER 图和 SQL。以下原有说明保留作为第一次检查基线。
