# API 与验收说明

> 历史接口说明：适用于 2026-10-21 初版。当前接口见 [API 设计](submission/04-api-design.adoc)及 [OpenAPI 契约](phase-one/apifox/openapi.json)。

启动方式见 [本地启动](first-check/local-run.md)。以下示例中的密码仅表示请求格式，运行时换成自己的临时演示密码。

## 访问边界（2026-10-21 更新）

先用启动时初始化的管理员调用 `POST /api/auth/login`，保存 `adminToken`。**下方所有用户、角色、系统、权限管理请求均必须添加 `Authorization: Bearer <adminToken>`，包括 GET 查询。** 普通用户返回 403，未登录或失效令牌返回 401。

- `POST /api/auth/login`：公开登录；请求和 LoginResponse 原有字段不变。
- `POST /api/auth/logout`：幂等注销当前 Bearer 会话，返回 204；已失效或缺少令牌仍为 204。
- `GET /api/auth/me`：需要有效登录，返回 `{userId, username, displayName, administrator, permissions}`。只能返回当前 JWT 身份，不接受目标用户 ID；权限每次重新计算。
- `GET /api/demo/expenses`：需要有效登录及 `expense:list`，返回固定演示数据。管理员不绕过业务权限。
- 其他 `/api/**` 默认要求管理员。静态网页和 `/hello` 不要求登录。H2 控制台默认关闭。

用户管理响应新增只读 `administrator` 字段，创建/更新请求不能设置它。创建用户后须单独设置密码才可登录。密码至少 8 个字符且 UTF-8 编码最多 72 字节（BCrypt 限制）。首次管理员不能停用或删除，返回 409；允许改密码，修改后也会撤销其原有会话。

参数错误返回 400，数据不存在 404，名称/编码冲突或关联约束冲突 409。错误体统一包含 `error` 和中文 `message`。新建系统/角色/权限的编码创建后不可修改，权限的所属系统创建后不可更换；权限无独立启停字段，以角色或所属系统状态控制。

普通用户不能修改任何账号密码、配置自己的角色或创建管理员。初始化规则见 [详细设计](first-check/design.md)。

## 建立 RBAC0 演示数据

1. 创建业务系统：`POST /api/systems`

```json
{"code":"finance","name":"财务系统","baseUrl":"http://finance.local"}
```

2. 创建用户：`POST /api/users`

```json
{"username":"zhangsan","displayName":"张三"}
```

3. 设置密码：`PUT /api/users/{userId}/password`

```json
{"password":"password-123"}
```

4. 创建角色：`POST /api/roles`

```json
{"code":"finance_viewer","name":"财务查看员","description":"查看报销"}
```

5. 创建菜单权限：`POST /api/permissions`

```json
{"systemId":1,"code":"expense:list","name":"查看报销","menuPath":"/expense/list"}
```

6. 给角色分配权限：`PUT /api/roles/{roleId}/permissions/{permissionId}`。该请求返回 `204`。

7. 给用户分配角色：`PUT /api/users/{userId}/roles/{roleId}`。该请求返回 `204`。

8. 查询用户最终权限：`GET /api/users/{userId}/permissions`。多个角色的权限会合并并去重。

9. 登录：`POST /api/auth/login`

```json
{"username":"zhangsan","password":"password-123"}
```

响应中的 `accessToken` 是 JWT，用于后续接口的 `Authorization: Bearer <accessToken>` 请求头，默认有效期为 2 小时。密码只保存 BCrypt 摘要，登录响应不会返回密码。

10. 调用受保护示例接口：`GET /api/demo/expenses`，并添加请求头 `Authorization: Bearer <accessToken>`。拥有 `expense:list` 时返回 `200`，移除角色后返回 `403`。

11. 注销：`POST /api/auth/logout`，添加同一个 `Authorization` 请求头；注销后令牌返回 `401`。

生产环境通过 `PERMISSION_JWT_SECRET` 设置至少 32 个字节的密钥；多台应用服务器必须使用同一密钥和同一个数据库。

## 新增会话与状态验收

完成上面的演示数据准备，并保留用户角色关系后，可分别执行以下场景。每个场景开始时重新登录，保存新的 `accessToken`；示例 ID 使用创建接口实际返回的值。

| 操作 | 验证 | 预期 |
|---|---|---|
| 同一账号登录两次，再 `PUT /api/users/{userId}/password` 设置新密码 | 两个旧 JWT 分别请求 `/api/demo/expenses` | 都为 `401`；新密码登录后可访问 |
| `PUT /api/users/{userId}`，提交 `{"displayName":"张三","enabled":false}` | 原 JWT 请求业务接口、原密码登录 | 都为 `401` |
| 上一步后把 `enabled` 改回 `true` | 先用原 JWT，再重新登录获取 JWT | 原 JWT 为 `401`，新 JWT 为 `200` |
| `PUT /api/systems/{systemId}`，提交 `{"name":"财务系统","enabled":false}` | 查询用户权限、登录、用有效 JWT 请求业务接口 | 不再包含该系统权限；业务接口 `403` |
| 将系统 `enabled` 改回 `true` | 用同一有效 JWT 请求业务接口 | 有角色权限时恢复为 `200` |
| `DELETE /api/users/{userId}` | 原 JWT 请求业务接口 | `401` |
| 同一账号登录两次，仅注销第一个 JWT | 两个 JWT 分别请求业务接口 | 第一个 `401`，第二个 `200` |

停用系统影响权限，不会注销账号的全部会话。注销仍为幂等操作：重复注销、未带令牌或令牌已失效均返回 `204`。

会话表不保存原始 JWT；旧版 JWT 在升级后因没有对应会话而失效。数据库升级脚本见 [migrations/20261019-login-sessions.sql](migrations/20261019-login-sessions.sql)。

所有管理类 API 已统一校验管理员身份，本阶段管理范围为全局。管理操作使用 adminToken，业务演示使用普通用户 accessToken，不能混用。

## 其他接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/users` | 查询用户 |
| PUT | `/api/users/{userId}` | 修改用户名称和启用状态 |
| DELETE | `/api/users/{userId}` | 删除用户及其角色关系 |
| GET | `/api/roles` | 查询角色 |
| PUT | `/api/roles/{roleId}` | 修改角色名称、描述和启用状态 |
| DELETE | `/api/roles/{roleId}` | 删除角色及其关联关系 |
| GET | `/api/systems` | 查询业务系统 |
| PUT | `/api/systems/{systemId}` | 修改业务系统 |
| DELETE | `/api/systems/{systemId}` | 删除系统及其权限 |
| GET | `/api/permissions?systemId={id}` | 查询菜单权限 |
| PUT | `/api/permissions/{permissionId}` | 修改权限名称和菜单路径 |
| DELETE | `/api/permissions/{permissionId}` | 删除权限及其角色关联 |
| GET | `/api/users/{userId}/roles` | 查询用户角色 |
| GET | `/api/roles/{roleId}/permissions` | 查询角色权限 |
| GET | `/api/users/{userId}/permissions/check?code=expense:list` | 管理端检查单项权限 |
| DELETE | `/api/users/{userId}/roles/{roleId}` | 回收用户角色 |
| DELETE | `/api/roles/{roleId}/permissions/{permissionId}` | 回收角色权限 |

生产环境可以执行 `database-mysql.sql` 建表，再通过 `application-prod.yml` 配置 MySQL；当前 `application.properties` 的 H2 配置用于本地开发和演示。
