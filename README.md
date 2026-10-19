# 统一权限管理系统

基于 Spring Boot、Spring Data JPA 和 RBAC0 模型实现的统一账号与权限管理系统基础版。项目按 `Controller → Service → Repository/Mapper` 分层，支持用户、角色、业务系统、菜单权限的管理，以及登录、角色分配和后端权限校验。

## 技术栈

- Java 21
- Spring Boot 4.0.8
- Spring Web MVC
- Spring Data JPA / Hibernate
- H2（本地开发）和 MySQL（生产环境）
- Maven
- BCrypt 密码摘要

## CloneFactory 对象映射

项目集成了编译期生成对象拷贝代码的 `CloneFactory`。实现源码和 Maven 工程位于
`third-party/clonefactory`，坐标为 `cn.edu.xmu:clonefactory:0.0.1-SNAPSHOT`。
首次构建时先把这个内置依赖安装到本地 Maven 仓库：

```powershell
$env:JAVA_HOME = 'C:\Users\姚景云\.jdks\corretto-21.0.12.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd D:\nified-permission-system\third-party\clonefactory
mvn "-Dmaven.repo.local=D:\CodexData\cache\m2" clean install
cd D:\nified-permission-system
mvn "-Dmaven.repo.local=D:\CodexData\cache\m2" clean test
```

业务 DTO 使用 `@CopyTo`、`@CopyFrom` 和 `@CopyNotNullTo` 声明映射关系，
`RbacService` 通过生成的 `CloneFactory.copy(...)` 与
`CloneFactory.copyNotNull(...)` 完成 DTO 和实体之间的转换。编译后生成的
`CloneFactory.java` 位于 `target/generated-sources/annotations`，不需要手动编辑。
更多注解和故障排查说明见 [docs/clonefactory.md](docs/clonefactory.md)。

## 当前功能

- 用户、角色、业务系统、菜单权限的增删改查
- 用户分配和回收角色
- 角色分配和回收权限
- RBAC0 权限并集计算，多个角色的权限自动合并去重
- BCrypt 密码保存和登录接口
- JWT Bearer Token 登录、注销和令牌校验
- 数据库持久化登录会话，注销状态在共享数据库的应用实例间生效
- 重置密码、停用或删除账号时撤销该账号的全部会话；重新启用后需重新登录
- 停用业务系统后，该系统权限从有效权限中排除
- 受保护示例接口的后端权限校验
- MySQL 建表脚本和生产环境配置模板

## 项目结构

```text
src/main/java/com/cdwy/permission
├── config       密码编码配置
├── controller   HTTP 接口和异常处理
├── dto          请求与响应对象
├── entity       JPA 持久化对象
├── repository   Spring Data JPA 数据访问层
└── service      RBAC0 和认证业务逻辑
third-party
└── clonefactory  CloneFactory 注解处理器源码
docs
├── database-mysql.sql
├── migrations      已有 MySQL 环境的增量脚本
├── reference-improvements.md
└── rbac-api.md
```

## 本地运行

项目已配置为使用 JDK 21。PowerShell 中执行：

```powershell
$env:JAVA_HOME = 'C:\Users\姚景云\.jdks\corretto-21.0.12.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd D:\nified-permission-system
mvn "-Dmaven.repo.local=D:\CodexData\cache\m2" spring-boot:run
```

默认端口为 `8080`。本地默认使用 H2 内存数据库，应用停止后演示数据会清空。

## 接口示例

1. 创建用户：`POST /api/users`

   ```json
   {"username":"zhangsan","displayName":"张三"}
   ```

2. 设置密码：`PUT /api/users/{userId}/password`

   ```json
   {"password":"password-123"}
   ```

3. 登录：`POST /api/auth/login`

   ```json
   {"username":"zhangsan","password":"password-123"}
   ```

   登录响应中的 `accessToken` 用于请求头：

   ```text
   Authorization: Bearer <accessToken>
   ```

4. 调用受保护接口：`GET /api/demo/expenses`

   用户拥有 `expense:list` 权限时返回 `200`，权限被回收或业务系统被停用时返回 `403`，令牌失效后返回 `401`。

目前用户、角色、业务系统和权限的管理 API 用于本地演示，尚未增加管理员身份认证和管理范围校验。当前受到登录及权限保护的示例接口是 `/api/demo/expenses`；完整管理端鉴权是下一步工作。

完整的 Postman 请求顺序和接口清单见 [docs/rbac-api.md](docs/rbac-api.md)。

可直接导入 Postman 的完整验收集合位于 [docs/postman/unified-permission-system.postman_collection.json](docs/postman/unified-permission-system.postman_collection.json)。导入后把 `baseUrl` 设置为应用地址，按编号顺序执行即可验证登录、JWT、权限回收和注销。

## 测试和构建

```powershell
mvn "-Dmaven.repo.local=D:\CodexData\cache\m2" clean test
mvn "-Dmaven.repo.local=D:\CodexData\cache\m2" clean package
```

集成测试覆盖 RBAC0 权限合并，以及通过 MockMvc 发起的 Controller → Service → 数据库调用：密码重置撤销全部旧会话、停用再启用不恢复旧令牌、删除账号、独立验证器共享注销状态、停用系统、权限回收、非法令牌、事务回滚，以及登录和密码重置的两种并发顺序。测试使用独立 H2 数据库，不启动监听端口；尚未完成真实 MySQL 多实例部署验证。

参考 RBAC-admin 后的改进说明、验收方式和后续优先级见 [docs/reference-improvements.md](docs/reference-improvements.md)。

RBAC0 模型、数据表、业务流程和验收范围见 [docs/rbac0-design.md](docs/rbac0-design.md)；两台服务器的 MySQL、应用包和环境变量部署步骤见 [docs/deployment-two-servers.md](docs/deployment-two-servers.md)。

## MySQL 部署

执行 [docs/database-mysql.sql](docs/database-mysql.sql) 创建生产数据库表，使用 `prod` 配置启动：

已有旧版数据库应先执行增量脚本 [docs/migrations/20261019-login-sessions.sql](docs/migrations/20261019-login-sessions.sql)，再统一升级应用实例。旧令牌没有数据库会话，升级后用户需重新登录。增量脚本需在部署时手动执行，应用不会自动修改生产表结构。

```powershell
$env:DB_URL = 'jdbc:mysql://数据库地址:3306/unified_permission?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME = 'unified_permission'
$env:DB_PASSWORD = '数据库密码'
mvn "-Dmaven.repo.local=D:\CodexData\cache\m2" "-Dspring-boot.run.profiles=prod" spring-boot:run
```

生产配置位于 `src/main/resources/application-prod.yml`，密码通过环境变量提供，不写入源码。

JWT 密钥通过 `PERMISSION_JWT_SECRET` 环境变量配置，令牌默认 2 小时过期。多个应用实例必须使用相同的 JWT 密钥和同一数据库。`sys_login_session` 只保存随机会话标识、用户和时间，不保存明文 JWT 或密码。注销删除当前会话；改密码、停用或删除账号删除该账号全部会话。校验同时要求 JWT 有效、数据库会话存在且账号启用。本地 H2 重启后账号和会话都会清空；MySQL 数据持久化时会话及注销结果可保留。
