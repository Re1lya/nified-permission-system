# 一期 API 设计

## 1 接口约定

一期接口以现有 Spring Boot REST 接口为基础，不在本轮强制改变所有旧客户端的返回结构。JSON 使用 UTF-8，编号为长整数，时间以 UTC 存储并以 ISO 格式或 JDBC 时间序列化输出。已有 DTO 多用 camelCase，新增列表部分来自 SQL，字段为 snake_case；界面已适配，后续统一包装必须版本化迁移。

旧管理接口 `/api/users`、`/api/roles`、`/api/systems`、`/api/permissions` 和 `/api/company/**` 默认只允许超级管理员。`/api/platform/**` 每个方法显式验证本人、超级管理员或部门范围。`/api/integration/**` 不接受普通用户令牌作为凭据，而是要求签名。OIDC 协议端点由 Spring Security 实现。

| 状态 | 含义 | 前端处理 |
| --- | --- | --- |
| 200、201 或 204 | 操作成功或无返回内容 | 显示成功并重新读取列表 |
| 400 | 字段格式错误 导入错误 签名事件格式错误 | 保留表单并显示具体信息 |
| 401 | 未登录 会话已撤销或失效 | 清除前端令牌并返回登录 |
| 403 | 身份有效但无权限 初始密码未修改 签名错误 | 保留会话，显示限制原因 |
| 404 | 资源不存在 | 提示刷新或重新选择 |
| 409 | 重复数据 关联约束 幂等内容冲突 | 不自动重试写入，提示核对 |

通用业务错误返回 `error` 和 `message`。OIDC 使用标准 OAuth 错误结构。部分框架级 400 和上传限制错误仍为框架返回，界面有状态码兜底。列表 page 从 0 开始、默认 size 为 25、最大 100；并非所有旧接口已有分页。

### 1.1 错误响应格式与业务含义

业务错误统一返回 JSON：`{"error":"<HTTP 状态短语>","message":"<中文原因>"}`。前端按状态码决定动作，按 message 向用户解释原因。下表列出各状态码的业务含义与典型 message。

| 状态 | error | 业务含义 | 典型 message |
| --- | --- | --- | --- |
| 400 | Bad Request | 字段校验失败、导入有错误、有效期或编码格式不合法、集成事件内容不完整 | 权限编码格式为 应用:模块:操作…；临时人员有效期最长 90 天 |
| 401 | Unauthorized | 未登录、令牌无效或过期、会话被撤销、账号停用或过期、权限版本已变化 | 登录状态无效或已过期，请重新登录；权限版本已改变，请刷新或重新获取令牌 |
| 403 | Forbidden | 身份有效但越权、超出部门范围、初始密码未修改、集成签名错误或过期 | 管理接口仅允许管理员访问；只能管理绑定部门的员工；请先修改初始密码 |
| 404 | Not Found | 资源不存在 | 账号不存在 |
| 409 | Conflict | 唯一键或外键冲突、仍被引用、幂等事件同编号不同内容、权限点已失效 | 角色仍被员工或岗位引用；同一请求编号不能对应不同内容 |

**与需求的偏离：** 原需求第 11 节要求“统一响应体包含业务码、消息与数据三段，HTTP 状态码仅表达协议语义”。一期沿用第一次检查已交付的契约，用 HTTP 状态码区分业务结果、成功时直接返回数据。原因是前端、Postman 集合与 9 个串联场景都依赖现有状态码；改为统一包装需要版本化迁移（例如新增 /api/v2 前缀），列入后续工作。

## 2 登录与自助接口

平台 JWT 默认有效期 15 分钟。POST `/api/auth/refresh` 携带当前令牌换取新令牌：只要会话仍存在、账号可用且最近 30 分钟内有操作，即使令牌刚过期也可以轮换，旧会话随即作废。管理界面在令牌使用超过 10 分钟或收到 401 时自动轮换一次。登录失败次数按解析后的真实账号累计，账号和手机号交替输错共享 5 次上限。

POST `/api/auth/login` 接收 username、password，成功返回 accessToken、userId、username、displayName、permissions。username 可填既有用户名或唯一手机号。返回令牌仅用于平台 Bearer API，不与 OIDC access token 混用。

GET `/api/platform/self` 返回当前用户编号、superAdmin、departmentAdmin、绑定部门、mustChangePassword、有效权限和权限版本。POST `/api/platform/self/password` 接收 oldPassword、newPassword，验证旧密码和复杂度，成功撤销旧会话。POST `/api/auth/logout` 撤销当前平台 JWT；它不是 OIDC 全局单点登出。

POST `/api/platform/self/sso` 使用有效平台 Bearer 建立服务器浏览器会话，防止匿名建立登录状态，并更新会话标识。GET `/api/platform/sessions` 仅列本人会话；DELETE 同路径加 sessionId 撤销指定会话，操作他人会话必须满足管理范围。

## 3 三表导入契约

POST `/api/company/imports/preview` 使用 multipart/form-data，三个文件字段固定为 departments、employees、permissions。仅接受当前三种表头布局，单工作表，每份非空且不超过 10 MB。服务解析 XLSX 中实际单元格，禁止公式和外部 XML 实体，不执行表单中的任何指令。

成功预览返回 previewId、fingerprint、expiresAt、counts、errorCount、warningCount、errors、warnings。counts 包含 departments、employees、posts、systems、modules、functions。每条问题含 sheet、row、message；row 为 0 表示整表规则。问题最多展示 200 条，数量是完整统计。预览不包含员工名单。

POST `/api/company/imports/commit` 接收 previewId 和 acknowledgeWarnings。预览只允许创建者在 30 分钟内提交。错误不为 0 或未确认警告时返回 400。事务成功返回 createdUsers、createdPermissions、unresolvedManagers、batchId、reused 等统计。已导入指纹返回 reused=true，不重新应用旧授权。

GET `/api/company/imports` 返回最近 50 批。GET `/api/company/employees` 支持分页、q 和 departmentId；GET `/api/company/catalog` 支持分页和功能搜索。POST `/api/company/posts/{id}/template` 接收 name，例如普通员工，只追加模板中已有权限到岗位角色，不授予员工角色，也不创建平台管理员。

## 4 管理边界接口

PUT `/api/platform/administrators/{id}` 接收 departmentId，仅超级管理员可设置精确部门绑定。DELETE 加部门编号解除绑定。后端拒绝部门管理员管理其他管理员或其他部门员工。

用户密码重置、状态变更、角色分配、回收及强制下线均位于 `/api/platform/users/{id}` 下。新用户删除接口采用逻辑停用，回收角色并撤销会话。旧 `/api/users/{id}` 删除仍有物理删除语义，新旧接口的删除语义按本节分别使用。

POST `/api/platform/organization/posts` 同时创建岗位和同名角色。指定 departmentId 时形成受管角色。超级管理员通过 grant-limits 接口指定部门可授予的权限；部门管理员修改角色权限时，同时检查角色归属和委派上限。共享岗位角色只能由超级管理员维护。

GET `/api/platform/sessions?scope=managed` 返回管理范围内普通员工的有效会话（超级管理员为全部，部门管理员为绑定部门），撤销沿用 DELETE `/api/platform/sessions/{id}`；默认 scope=self 只返回本人会话。GET `/api/platform/approvals` 对部门管理员开放，只返回绑定部门员工的审批记录。审计事件同时记录操作人部门与被操作员工部门，部门管理员可看到超级管理员对本部门员工的操作；kind 过滤按前缀匹配并转义通配符。

POST `/api/platform/initial-passwords` 请求体 departmentId、可选 limit（≤ 200），为该部门从未设置密码的员工生成随机初始密码（12 位，含四类字符），标记首次登录须修改，并只在本响应中返回一次；GET 同路径加 departmentId 返回待生成人数。审计只记录数量（BR-03）。

权限点增加 type（MENU / BUTTON / API，默认 API）与 active。编码须为小写、冒号分隔、最多三段（如 oa:leave:approve），否则 400。DELETE `/api/permissions/{id}` 改为标记失效：既有授权保留但不再生效，失效权限点不能再授予（409），PUT 同路径带 active=true 可恢复。删除业务系统前须其权限点全部失效且无角色引用（BR-16）。

## 5 OA 和 HR 签名

POST `/api/integration/hr` 或 `/api/integration/oa`。三个请求头必需：X-Request-Id、X-Timestamp、X-Signature。请求编号使用 1 到 64 位字母、数字、下划线或连字符。时间戳为 Unix 秒，与服务器相差不超过 300 秒。

签名采用 HMAC-SHA256，密钥为该来源独立配置的环境变量。待签名文本严格为 source、timestamp、requestId、原始 JSON 字符串，以换行拼接，共四段。签名输出小写十六进制。原始请求体的空白改变也会改变签名，因此不能签名后重新格式化 JSON。

示例签名输入：
```
hr
时间戳秒
join_demo_001
{"action":"JOIN","username":"demo_new","displayName":"演示员工","departmentId":1,"postId":1,"phone":"13900000000","company":"演示公司"}
```

集成密钥未配置或不足 32 字符时入口关闭。验签通过后再解析业务事件；source 与 requestId 构成唯一键，digest 记录内容摘要，相同事件重发返回 reused=true，同编号不同内容返回 409。时间戳抵御过期请求，幂等记录抵御重复业务执行。

## 6 HR 事件契约

| action | 必需数据 | 状态变化 |
| --- | --- | --- |
| JOIN | username displayName departmentId postId phone company | 新建普通账号和档案，无密码，无角色 |
| TRANSFER | userId departmentId postId phone company roleIds | 校验新岗位后事务内替换组织及完整角色集合 |
| LEAVE | userId | 停用账号，删除角色关联，撤销全部 JWT 会话 |

TRANSFER 的 roleIds 必须显式提供；空数组表示回收全部。当前只允许赋予新岗位对应的启用角色。用户必须存在导入或 HR 员工档案。超级管理员和部门管理员不允许由 HR 自动变更。部门和岗位必须预先存在，通过三表导入准备。

## 7 OA 事件契约

员工 POST `/api/platform/self/applications`，请求体 roleId，平台生成 PENDING 申请并返回 id、request_id、decision。角色必须是启用的岗位角色。普通用户不能在此阶段直接授予自己角色。

OA 回调请求体包含 approvalId 和 decision。decision 只允许 APPROVED 或 REJECTED。批准授予申请中的角色，拒绝只更新审批结论。必须锁定申请记录，防止同一审批重复处理。申请人停用或过期时拒绝批准。回调不可用任意 userId 和 roleId 替换申请内容。

## 8 OIDC 协议和业务鉴权

发现文档 `/.well-known/openid-configuration`，授权端点 `/oauth2/authorize`，令牌端点 `/oauth2/token`，密钥端点 `/oauth2/jwks`，用户信息端点 `/userinfo`，撤销和内省端点由标准授权服务器提供。可通过发现文档核对实际端点。

本地固定两个客户端：oa-demo 回调为 http://127.0.0.1:18101/callback，hr-demo 回调为 http://127.0.0.1:18102/callback。默认是 public client，使用 authorization_code + PKCE S256、精确回调、openid/profile scope；客户端校验 state、nonce、RS256 签名、issuer、audience 和 exp，授权码只能消费一次。配置 PERMISSION_OIDC_CLIENT_SECRET（至少 32 字符）后，两个客户端还可用 client_secret_post / client_secret_basic 认证，此时授权服务器签发 refresh_token（8 小时，每次刷新轮换、旧刷新令牌作废）；按 OAuth 规范，public client 不签发刷新令牌（UC-A07）。

服务运行时生成 RSA 密钥。客户端、授权记录、同意记录、签名密钥均为进程内保存，重启即失效，未开放动态注册。刷新时重新检查账号是否停用或过期，并写入当前 permission_version。授权码被重放时，服务器撤销该授权码签发的全部令牌。OIDC 授权后的业务请求仍需独立检查 RBAC0 权限。

GET `/api/oidc/permissions/check?code=...&clientId=...` 使用 OIDC Bearer，检查 access token 的签名、audience、有效期、授权记录、账号状态和 permission_version，再判断权限；允许时返回 grantedBy（提供该权限的启用角色）与 permissionVersion，拒绝时写 ACCESS_DENIED 审计（UC-B07）。强制下线、停用、离职、改密都会使权限版本号加一，因此旧 OIDC 令牌在下一次校验时返回 401，应用用刷新令牌或重新授权获取新令牌。GET `/api/oidc/permissions` 返回本人权限编码集合；`/userinfo` 同样携带 permissions 与 permission_version（UC-B08）。GET `/api/oidc/directory/departments` 与 `/api/oidc/directory/users`（分页，可按 departmentId 过滤）向接入应用下发组织数据，不含电话等联系方式（UC-D02）。

## 9 调用流程与事务

导入：上传文件 → XLSX 安全解析 → 关系与操作校验 → 预览确认 → 一个事务创建主数据和模板 → 记录批次。提交失败不保留半批数据。

业务访问：Bearer 验签 → 查账号和数据库会话 → 检查空闲时间与过期策略 → 查启用角色和业务系统 → 合并权限 → 返回 200 或 403。未登录或会话无效返回 401。

HR 调岗：来源验签 → 查幂等事件 → 锁定账号 → 校验组织和岗位角色 → 删除旧角色 → 更新档案 → 写新角色 → 增加权限版本 → 写事件。OA：来源验签 → 查事件 → 锁定申请 → 验证状态 → 按审批结果授权 → 写事件。

### 9.1 外部与临时人员接口（UC-C05）

POST /api/platform/external-users 由超级管理员或绑定部门的部门管理员调用，请求体：username、displayName、personType（EXTERNAL 或 TEMPORARY）、expiresOn（yyyy-MM-dd）、departmentId、postId、company、phone、password。外部人员有效期最长 365 天，临时人员最长 90 天，不能早于今天，否则返回 400；部门不在管理范围返回 403。成功返回 id、personType、expiresOn。账号无角色，初始密码标记为首次登录须修改。

PUT /api/platform/external-users/{id}/expiry 请求体 expiresOn，按原人员类型重新校验上限；对正式员工返回 400。续期使权限版本号加一并写审计 EXTERNAL_RENEW。

GET /api/platform/external-users?remindDays=14 返回管理范围内的外部与临时人员：id、username、display_name、person_type、company、department_id、post_id、enabled、expires_on、days_left、status（ACTIVE / EXPIRING / EXPIRED）。有效期截止为到期日次日 0 点（Asia/Shanghai），到期后登录和已有令牌都返回 401。

### 9.2 主要接口响应示例

以下示例来自本机演示实例，编号与令牌已替换为示意值。

登录成功（POST /api/auth/login，200）：

```
{"accessToken":"eyJ…","userId":3,"username":"demo_li","displayName":"演示李","permissions":[{"id":7,"code":"expense:list","name":"查看报销","type":"MENU","active":true,"systemId":2}]}
```

令牌轮换（POST /api/auth/refresh，200；旧令牌随即失效）：

```
{"accessToken":"eyJ…"}
```

越权（普通用户 GET /api/users，403）：

```
{"error":"Forbidden","message":"管理接口仅允许管理员访问"}
```

权限点格式错误（POST /api/permissions，400）：

```
{"error":"Bad Request","message":"code: 权限编码格式为 应用:模块:操作，小写字母、数字、下划线或连字符，最多三段"}
```

外部人员创建（POST /api/platform/external-users，200）与到期提醒（GET /api/platform/external-users）：

```
{"id":5,"personType":"TEMPORARY","expiresOn":"2026-10-31"}
[{"id":5,"username":"ext_vendor01","person_type":"TEMPORARY","company":"演示外包公司","department_id":2,"expires_on":"2026-10-31","days_left":9,"status":"EXPIRING"}]
```

初始密码（POST /api/platform/initial-passwords，200；密码只在此响应出现一次）：

```
{"departmentId":2,"issued":[{"username":"hr_demo_cfaf","displayName":"演示入职员工","initialPassword":"…"}],"remaining":0}
```

接入应用鉴权（GET /api/oidc/permissions/check，200 / 403 / 401）：

```
{"allowed":true,"userId":3,"code":"expense:list","grantedBy":["expense_viewer"],"permissionVersion":4}
{"error":"Forbidden","message":"用户没有权限: expense:approve"}
{"error":"Unauthorized","message":"权限版本已改变，请刷新或重新获取令牌"}
```

权限集合与组织数据下发（GET /api/oidc/permissions、/api/oidc/directory/users）：

```
{"userId":3,"permissionVersion":4,"permissions":["expense:list"]}
{"total":6,"page":0,"size":100,"items":[{"id":2,"username":"demo_zhang","display_name":"演示张","enabled":true,"department_id":2,"post_id":1,"person_type":"EMPLOYEE"}]}
```

HR / OA 签名事件（POST /api/integration/hr，200 / 409）：

```
{"reused":false,"result":"SUCCESS"}
{"reused":true,"result":"SUCCESS"}
{"error":"Conflict","message":"同一请求编号不能对应不同内容"}
```

## 10 Apifox 设计与检查

接口契约见 apifox/openapi.json（OpenAPI 3.0.3）。在 Apifox 项目设置中导入，使用本地环境 baseUrl=http://127.0.0.1:18081；令牌和管理员口令只填写在本地私密环境。接口按认证、公司数据、范围管理、集成事件、业务权限分组。

按 apifox/checks.md 建立检查场景并配置对应的后置断言。重点检查登录、权限并集、撤权 403、注销 401、普通用户管理拒绝和三表幂等。OpenAPI 用于接口与模型导入；检查场景单独配置并在 Apifox 中运行，运行后由 Apifox 导出报告。

每次接口调整，同时更新 Spec 编号对应的 OpenAPI、页面调用和检查条件。当前本地运行证据保存在 verification.json 和 http-verification.json。


Apifox 项目已在 CDWY 团队创建：https://app.apifox.com/project/8955177 。实际导入 68 个接口、20 个数据模型、1 个本地环境、3 个鉴权组件。导入截图：images/apifox-import.png。


已保存 Apifox 场景 SESSION-01（编号 8875512）。本地 CLI 场景文件为 apifox/session-01.apifox-cli.json，含 401 状态断言及错误响应结构校验；本次运行 1 个请求、1 条断言通过。报告为 apifox/session-01-report.html 和 .json。复现命令：apifox run docs/phase-one/apifox/session-01.apifox-cli.json -n 1 -r cli,html,json。网页跨域调试时使用 Apifox 扩展或桌面端；本机 CLI 可直接访问 18081。

### 10.1 九个串联场景的完整运行

checks.md 的 9 个场景及 EXT-01 已写成 apifox/phase-one-scenarios.postman_collection.json（Postman v2.1，由 scripts/build-acceptance-scenarios.py 生成），可直接导入 Apifox，也可用 newman 运行；断言脚本使用 pm 语法，两者通用。集合只含变量名，运行时提供 baseUrl、adminUser、adminPassword、hrSecret、oaSecret、workbookDir。

2026-10-21 在本机隔离实例（phase-one 配置、H2、端口 18091、三份虚构演示表）上用 newman 运行：49 个请求、65 条断言全部通过，用时约 5.3 秒。

| 场景 | 请求 | 断言 | 覆盖的反向用例 |
| --- | --- | --- | --- |
| AUTH-01 登录与密码策略 | 2 | 4 | 错误密码 401 |
| RBAC-01 准备 | 12 | 14 | — |
| SCOPE-01 越权与无效令牌 | 4 | 4 | 普通用户管理 API 403、无令牌 401、伪造令牌 401 |
| RBAC-01 权限并集与回收 | 5 | 5 | 全部来源回收后同一令牌 403 |
| SESSION-01 注销 | 2 | 2 | 注销后旧令牌 401 |
| IMPORT-01 三表导入 | 6 | 9 | 重复提交 reused=true |
| HR-01 签名事件 | 4 | 6 | 重发 reused=true、同编号不同内容 409、过期时间戳 403 |
| OA-01 申请与审批 | 7 | 12 | 错误签名 403、重复回调 reused=true |
| EXT-01 外部与临时人员有效期 | 7 | 9 | 临时人员超过 90 天 400、普通用户 403 |

运行明细：apifox/phase-one-scenarios-report.json（每步方法、路径、状态码与断言结果）和 apifox/phase-one-scenarios-cli.txt。报告不保存令牌、口令与签名密钥。复现命令（先运行 scripts/make-demo-workbooks.py 生成虚构表）：

```
npx newman run docs/phase-one/apifox/phase-one-scenarios.postman_collection.json --env-var baseUrl=http://127.0.0.1:18091 --env-var adminUser=… --env-var adminPassword=… --env-var hrSecret=… --env-var oaSecret=… --env-var workbookDir=output/demo-workbooks
```

运行中观察到一次异常：服务启动后的第一次登录返回 500，日志为 Jackson 类 POJOPropertyBuilder 的 VerifyError；随后的请求和重新启动后的完整运行都正常。打包的 jackson-databind 3.1.5 与 Maven 仓库校验和一致，原因尚未查明，需在答辩前复现排查。

### 10.2 OpenAPI 与实际路由的对应

apifox/openapi.json 由 scripts/build-design-assets.py 按测试导出的实际路由生成，当前 77 个操作。Spring 注册的 Controller 路由共 80 个，差额 3 个是有意不纳入接口契约的页面：OIDC 登录页 GET /login、授权同意页 GET /consent，以及第一次检查保留的 GET /hello 演示。OIDC 协议端点（/oauth2/*、/userinfo、/.well-known/*）由授权服务器过滤器提供，见第 8 节。
