"""Generate the course submission AsciiDoc set from current design sources and actual test evidence.
Run export-evidence.py after a successful `mvn clean verify`, then run this script.
Personal revision PDFs and operational credentials are not inputs to this script.
"""
from pathlib import Path
import json
import re

R = Path(__file__).resolve().parents[1]
D = R / 'docs/submission'
D.mkdir(exist_ok=True)
P = R / 'docs/phase-one'
summary = json.loads((D / 'evidence/test-summary.json').read_text())
counts = {key: sum(int(s[key]) for s in summary['suites']) for key in ('tests','failures','errors','skipped')}
passed = counts['tests'] - counts['failures'] - counts['errors'] - counts['skipped']
cov = summary['coverage']
header = ':lang: zh-CN\n:toc: left\n:toc-title: 目录\n:sectnums:\n:sectanchors:\n:source-highlighter: highlight.js\n:icons: font\n\n小组成员：温家正、姚景云、杜奕含、陈浩贤。\n\n更新日期：2026-10-26。依据当前一期实现；历史记录均保留其原日期。\n\n'
def write(name, title, body):
    (D / name).write_text('= '+title+'\n'+header+body.strip()+'\n', encoding='utf-8')
def inline(text):
    text = re.sub(r'\*\*(.*?)\*\*',r'*\1*', text)
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', lambda m: ('link:'+m[2]+'['+m[1]+']'), text)
    return text

def markdown(source):
    lines=source.splitlines(); result=[]; i=0
    while i<len(lines):
        line=lines[i]
        if line.startswith('# '): i+=1; continue
        if line.startswith('```'):
            lang=line[3:].strip() or 'text'; result += ['[source,'+lang+']','----']; i+=1
            while i<len(lines) and not lines[i].startswith('```'):
                result.append(lines[i]); i+=1
            result += ['----','']; i+=1; continue
        if line.startswith('|'):
            rows=[]
            while i<len(lines) and lines[i].startswith('|'):
                cells=[v.strip() for v in lines[i].strip('|').split('|')]
                if not all(re.fullmatch(r'[-: ]+',v) for v in cells):rows.append(cells)
                i+=1
            if rows:
                result += ['[cols="'+','.join(['1']*len(rows[0]))+'",options="header"]','|===']
                for row in rows:
                    result.append('|'+' |'.join(inline(v) for v in row))
                    result.append('')
                result += ['|===','']
            continue
        m=re.match(r'^(#{2,6}) (.*)',line)
        if m: line='='*len(m[1])+' '+re.sub(r'^\d+(?:\.\d+)*\s+', '',m[2])
        else: line=inline(line)
        if line.startswith('- '):line='* '+line[2:]
        result.append(line); i+=1
    return '\n'.join(result)

req=(P/'requirements.md').read_text()
req=re.sub(r'^> 本文件保留.*\n', '', req, flags=re.M)
req=req.replace('本次在代码补充与设计修订后，继续执行真实 MySQL 建库、隔离旧结构升级和云端 HTTPS 接口联调，实际通过项目见 cloud-verification.json。没有外部 OA/HR 产品，使用遵循接口契约的调用端验证；没有运行项目单元测试、集成测试套件或压力测试。旧版 44 项测试仅对应旧提交 01b94f06，不能用于证明本次变更正确。',
    '2026-10-22 完成的 MySQL 建库、隔离旧结构升级和云端接口联调仍作为历史记录保留；没有外部 OA/HR 产品。2026-10-26 对当前代码执行完整 Maven 测试，结果见本目录《JaCoCo 与测试说明》。本次没有重跑云端迁移、外部产品联调或压力测试。')
req=req.replace('实际通过的本轮联调项目','2026-10-22 实际通过的联调项目').replace('本轮实际联调项目见 cloud-verification.json','2026-10-22 联调项目见 ../phase-one/cloud-verification.json')
req=req.replace('四份 Word 分别说明需求、界面、API 和数据库；', '本目录 .adoc 为当前提交入口，四份历史 Word 分别说明需求、界面、API 和数据库；')
write('01-requirements.adoc','统一权限管理系统：需求与实现说明',
      '原始需求基线：link:../phase-one/需求分析基线-V1.0.md[DOC-01 V1.0]。设计说明不会把尚未验收的需求当成已完成验收。\n\n'+markdown(req))

write('02-detailed-design.adoc','统一权限管理系统：详细设计',r'''
== 设计目标与边界

系统解决多业务系统登录分散、账号生命周期不一致和权限难以统一管理的问题。
一期采用 RBAC0：用户通过角色获取权限，多个启用角色取有效权限的并集；没有角色继承或互斥。
岗位与角色保持一一映射；平台管理身份独立于业务岗位角色。
业务报销页面为固定数据的鉴权示例，不是完整报销系统。OA/HR 为接口契约接入，尚无外部产品。

== 模块职责与调用关系

[cols="2,3,4",options="header"]
|===
|层或模块 |主要实现 |职责
|界面 |`frontend/src/App.vue`、`CompletionPanel.vue` |导航、表单、能力显示、请求与令牌刷新；不承担最终授权决策
|HTTP 边界 |`controller/*`、`platform/*Controller`、`CompanyController` |绑定输入、响应协议、提取当前身份
|安全边界 |`ApiAccessConfig`、`JwtTokenService`、`OidcConfig` |管理入口鉴权、业务权限检查、平台 JWT 与标准 OIDC
|授权领域 |`RbacService`、`UserAccount`、`Role`、`Permission` |用户角色与角色权限操作、有效权限并集、引用检查
|组织与生命周期 |`OrganizationMaintenance`、`HrSynchronization`、`AccountNaming` |部门岗位、拼音账号、调岗离职、管理员范围
|外部集成 |`IntegrationService`、`SyncAdministration` |签名、幂等、原子批次、失败记录、对账
|应用接入 |`ApplicationController`、`ApplicationProtocol` |OIDC 配置、接入验收和 LEGACY 隔离入口
|复核与审计 |`AuditLifecycle`、`AuditRetention`、`PlatformStore` |权限复核、在线与归档查询、审计范围
|持久化 |JPA Repository、`PlatformStore` / JdbcTemplate |实体关联、事务内读写、范围 SQL 与协议持久化
|===

普通 RBAC 请求为 Controller → RbacService → Repository → 数据库。
平台管理模块还包含控制器直接调用 JDBC 的路径；当前不是严格的纯领域驱动分层。
输入 DTO 与实体、输出 DTO 分开；CloneFactory 用于对象复制，但管理身份不能通过普通用户 DTO 批量赋值。

== 核心对象关系

[source,text]
----
UserAccount（人员 + 一对一账号聚合）
  1 ---- 0..* UserRole 0..* ---- 1 Role
Role
  1 ---- 0..* RolePermission 0..* ---- 1 Permission
BusinessSystem
  1 ---- 0..* Permission
Department
  1 ---- 0..* EmployeeProfile ---- 1 UserAccount
Post ---- 1 Role
UserAccount
  1 ---- 0..* LoginSession
----

`sys_user` 与 `sys_account` 分表，Java 使用 `@SecondaryTable` 聚合；账号凭证变化不改变人员编号。
HR 的 `externalId` 经 `hr_identity` 映射本地编号，既不是登录名，也不是天然数据库主键。
`UserRole` 与 `RolePermission` 是关联实体，以唯一约束阻止重复授权。
角色之间没有继承关系，多个角色重叠授权时回收一个角色仍保留其他角色的有效授权。

== 关键交互与事务

=== 登录、业务鉴权与刷新

. 登录请求经账号或唯一手机号定位用户；失败计数和锁定以解析后的账号统一计算。
. 校验 BCrypt 密码、启用状态、有效期与锁定状态；用户行锁协调登录与改密并发。
. 生成带 `jti`、`sub`、`iat`、`exp`、`permission_version` 的平台 JWT，保存数据库会话。
. 业务请求先校验签名、会话、有效期、空闲时间与权限版本，再计算当前有效权限。
. 授权变化后旧令牌返回 401。仍存在且有效的数据库会话可通过刷新接口轮换令牌，无需重新输入密码。
. 禁用、离职、改密和强制下线撤销会话；旧令牌不能刷新。403 表示身份有效但权限不足。

平台 JWT 的刷新不等于 OAuth2 刷新令牌；两条协议路径各自实现，不能混用。
管理员也不自动具有业务权限，例如没有 `expense:list` 时不能访问报销示例。

=== 岗位授权与撤权

. 服务端校验操作者身份、目标员工部门、目标角色和委派权限上限。
. 用户角色或角色权限关系变更与权限版本更新在事务内提交。
. 有效权限只取启用角色、启用应用、有效权限点的并集。
. 权限点删除是标记失效，保留历史授权；角色或应用删除先检查引用，再逻辑删除并改写唯一编码。
. 当前权限目录联表读取未删除应用，避免历史权限引用被逻辑删除的父对象导致列表报错。

=== HR 组织同步与员工生命周期

. 验证来源、时间戳、请求号和原始报文签名。
. 依赖顺序为父部门、子部门、岗位、员工；FULL/INCREMENTAL 整批原子处理。
. 新员工生成拼音账号及随机初始密码，设置首次改密；不自动授予岗位角色。
. 调岗验证完整新角色集合，在同一事务回收旧角色、更新组织、分配新角色并通知两部门。
. 离职停用账号、回收授权、撤销会话；业务失败回滚，失败摘要另行保存。
. 同号同内容复用结果，同号不同内容返回冲突；修正内容必须使用新请求号。

=== OA 申请与结果回调

. OA 发起申请，签名 `APPLICATION` 登记申请人及一个岗位角色。
. OA 可查询有效权限及申请状态；平台保留本人状态查询，没有平台自建申请 POST。
. 回调锁定申请记录；批准前验证账号状态，然后授权并更新结论。
. 相同结论重复回调不重复授权；驳回或取消不授予，已处理的不同结论拒绝覆盖。
. 幂等结果保存 JSON 文本；JDBC 在结果集有效期间将 CLOB 物化为字符串，兼容 H2 与 MySQL。

=== OIDC 应用接入

先建立业务目录，再配置不可变 clientId、入口和精确回调；采用授权码加 S256 PKCE。
配置改变后撤销旧授权并回到 DRAFT。管理员记录登录、回调、鉴权、撤权和隔离证据后才能 ACTIVE。
门户还要同时满足应用启用、已验收和用户获授权三个条件。
OIDC 客户端、授权、同意、签名密钥和浏览器会话持久化。LEGACY 只聚合入口并保留原系统登录。
接入验收接口保存人工结果，不自动证明五项验证已执行。

=== 权限复核与审计

复核按受管员工与角色逐项执行 KEEP/REVOKE，理由必填；回收关系和复核结果同事务提交。
审计保存当时的部门归属；在线与归档查询均限制部门范围。
归档将超过十二个日历月的记录按最多一千条批次移入归档表，同一事务复制并移除在线副本。
审计写入独立事务以保留失败行为；因此审计结果字段应结合业务事务和后续记录解释。

== 设计选择、理由与限制

[cols="2,3,3",options="header"]
|===
|选择 |解决的问题 |限制或代价
|组合关系实现 RBAC0 |多岗位兼任、授权复用；避免把岗位名称硬编码成管理权限 |多表查询；不支持角色继承和互斥
|人员、凭证分离 |HR 身份稳定，凭证单独管理 |Java 聚合跨两个物理表，迁移需保持关联
|事务加数据库约束 |防止半次调岗、重复关系与孤立授权 |并发冲突仍需返回明确错误并由调用端重试
|JWT 加数据库会话及版本 |支持撤权、改密下线与无密码刷新 |每次校验需要访问服务端状态，不是纯无状态 JWT
|管理范围独立建模 |部门管理员不能越权到子部门或其他管理员 |管理能力与业务权限需要分别维护
|协议适配边界 |应用差异集中在接入处 |一期仅实现 OIDC；不能宣称 SAML/CAS/LDAP 已实现
|外部编号、摘要与幂等记录 |重复投递不会重复授权，外部名称可变 |同步仍需遵守依赖顺序；外部系统未接入验收
|===

== 异常响应与代码入口

参数不合法返回 400；未认证或旧版本令牌 401；越权 403；资源不存在 404；重复或引用冲突 409。
前端按错误类型反馈并重新取数，不通过隐藏按钮代替后端授权。

代码入口：link:../../src/main/java/com/cdwy/permission/[后端包]、link:../../frontend/src/[前端]。
接口与数据库分别见 link:04-api-design.adoc[API 设计]、link:03-database-design.adoc[数据库设计]。
验证范围见 link:06-jacoco.adoc[测试说明]；未执行的性能、容量、多实例和外部产品验收不作通过声明。
''')

catalog=json.loads((P/'schema-catalog.json').read_text())
ddl=(P/'database-mysql-phase-one.sql').read_text()
dbintro=r'''
== 数据域与模型

当前共 38 张表，包含业务表、OAuth2 表及 JDBC HTTP 会话表。
结构来源为当前实体映射和仓库内协议 schema；完整 DDL 以配套 SQL 为准。

[cols="2,4,4",options="header"]
|===
|数据域 |主要表 |关联与规则
|身份与组织 |sys_user、sys_account、employee_profile、hr_identity、sys_department、sys_post |账号以 user_id 一对一关联人员；外部编号按 kind + external_id 定位；岗位关联角色
|RBAC |sys_role、sys_permission、sys_user_role、sys_role_permission、biz_system |两组多对多关系；唯一约束避免重复授权；权限属于应用
|管理范围 |admin_department、managed_role、department_permission |显式绑定受管部门、角色和委派权限上限；不自动包含子部门
|目录导入 |sys_module、sys_function、permission_detail、permission_template、company_import_batch |系统—模块—功能—操作；导入摘要幂等；“无”不授予
|接入与协议 |application_access、oauth2_registered_client、oauth2_authorization、oauth2_authorization_consent |业务系统对应接入配置；标准 JDBC 协议表保存授权状态
|会话与凭证策略 |sys_login_session、session_activity、account_policy、login_attempt、platform_signing_key、SPRING_SESSION、SPRING_SESSION_ATTRIBUTES |平台会话和浏览器会话分开；版本、空闲期、锁定与有效期共同校验
|审计与协作 |audit_event、audit_archive、permission_review、approval_record、integration_event、sync_failure、platform_notification、data_dictionary |审计部门快照；审批幂等；复核；同步异常及通知
|===

== 一致性、约束与历史保留

* `sys_account.user_id` 是主键并关联人员；登录名唯一，密码只存 BCrypt 摘要。
* 用户角色和角色权限关系具有复合唯一约束。应用编码、角色编码、权限编码也受唯一约束。
* `hr_identity` 是多态外部编号映射，其 local_id 依 kind 由服务校验；不能把它误写为统一数据库外键。
* 部门、岗位、角色和应用软删除前检查引用；用户删除改写旧登录名、清空凭证并保留历史关联。
* 权限点失效保留历史授权，查询必须排除已删除父应用；失效权限不能参与有效权限并集。
* 调岗、授权和权限版本更新保持事务一致；OA 审批使用行锁；幂等编号与摘要一起约束重放行为。
* 审计归档保留原编号和发生时部门。业务接口不提供审计修改或删除功能。

== 表结构字典

下列 SQL 片段逐表列出字段、数据类型、非空与主键；跨表外键、唯一约束和索引见后附完整 DDL。
'''
for t in catalog:
    dbintro += '\n=== `'+t['name']+'`\n\n[source,sql]\n----\nCREATE TABLE '+t['name']+' (\n    '+t['ddl'].strip()+'\n);\n----\n'
dbintro += '\n== 完整建表、约束和索引\n\n[source,sql]\n----\n'+ddl+'\n----\n'
dbintro += r'''
== 新库与旧库的适用范围

link:../phase-one/database-mysql-phase-one.sql[全量 SQL] 只用于已选定的空数据库。
link:../migrations/20261022-phase-one-completion.sql[升级 SQL] 只适用于旧提交 01b94f06 的二十五表结构，执行前停止写入、核对前提并备份。
MySQL DDL 隐式提交；不能把部分失败的升级当作可随意重跑的事务。

2026-10-22 的真实 MySQL 新建和隔离旧结构升级证据见 link:../phase-one/cloud-verification.json[历史联调记录]。
本次 Maven 使用 H2，只验证其实际运行路径；不能替代 MySQL 方言、迁移、容量和备份恢复验收。
'''
write('03-database-design.adoc','统一权限管理系统：数据库设计',dbintro)
api=markdown((P/'api-design.md').read_text())
api=api.replace('外部地址要求 HTTPS，回环演示允许 HTTP。', '外部地址默认要求 HTTPS；回环演示及显式配置的课程 HTTP 演示源允许 HTTP，配置见 ../phase-one/http-access.md。')
write('04-api-design.adoc','统一权限管理系统：API 设计',
      '接口清单为 98 个业务控制器操作。OIDC 协议端点由 Spring Authorization Server 提供，不与业务控制器计数混淆。\n\nOpenAPI：link:../phase-one/apifox/openapi.json[可导入接口契约]。\n\n'+api)
write('05-ui-design.adoc','统一权限管理系统：界面设计',r'''
== 页面结构与身份

前端为 Vue 3 + TypeScript + Tailwind，构建资源位于后端 `static/ui`；界面与 API 同源。
登录后读取本人身份、具名管理能力和有效业务权限；首次改密账号进入个人中心。

[cols="2,4,4",options="header"]
|===
|身份 |页面范围 |后台边界
|超级管理员 |用户、角色、权限、组织、应用接入、管理员成员、审计、同步和复核 |内置管理员受保护；业务权限不自动授予
|部门管理员 |受管员工、密码发放、会话、授权、复核、审计及组织只读 |仅显式绑定部门；不能维护其他管理员；不自动包含子部门
|普通员工 |应用门户、本人权限、本人会话、个人资料与密码、OA 申请状态 |只能访问本人数据及获授权业务功能
|===

== 页面与交互

[cols="2,4,4",options="header"]
|===
|页面 |主要输入或动作 |结果与异常反馈
|登录与个人中心 |账号/唯一手机号、密码；本人改密 |失败提示、锁定提示；改密后旧会话失效
|用户与初始密码 |按部门查员工、停用、下线、一次性领取密码 |执行范围检查；重复领取不再次返回已发放密码
|角色与权限 |维护角色、权限点；配置用户角色与角色权限 |重复操作幂等；失效点不可新授权；引用冲突提示
|组织与三表导入 |部门、岗位、员工表和权限清单预览后确认 |先展示错误警告；不自动授予员工角色；确认后事务提交
|功能权限目录 |按系统、模块、功能、操作浏览和搜索 |“无”不授予；“全部”只展开已有具体操作
|应用接入与门户 |clientId、入口、回调、协议、密钥及验收证据 |密钥不回显；修改后待验收；门户需启用、验收、授权
|OA 申请状态 |查看已登记申请，跳转 OA 发起 |没有可用 OA 入口时提示配置；平台不自建审批流
|复核与审计 |员工角色、KEEP/REVOKE、理由；时间类型过滤 |复核提交重新检查关系；归档与在线范围一致
|同步异常与通知 |请求编号、摘要、状态和处理原因 |不展示原始 HR 报文或密钥；源系统修复后重新签名发送
|会话与异常页 |查看本人/受管会话、下线；403/404 |403 保留身份；401 尝试刷新，失败回到登录
|===

== 当前流程设计示意

下列图片来自仓库中 2026-10-22 的设计示意，*不是本次运行截图或验收证据*。
实际交互以 `App.vue` 与 `CompletionPanel.vue` 为准；本次没有逐页浏览器回归。

.应用接入配置与人工验收
image::../phase-one/images/design-applications-current.png[应用接入设计示意,width=1000]

.员工角色逐项复核
image::../phase-one/images/design-review-current.png[权限复核设计示意,width=1000]

.在线与归档审计查询
image::../phase-one/images/design-audit-current.png[审计设计示意,width=1000]

== 前后端一致性

导航或按钮隐藏仅改善交互；服务端仍复核身份、目标用户、部门范围及委派上限。
写入期间防止重复点击；成功后重新查询。409 表示引用或重复冲突，提示用户刷新数据。
授权变化后旧 JWT 返回 401，前端可以旋转仍有效会话；会话已撤销则需重新登录。
''')

suite_rows='\n'.join('|'+s['name'].split('.')[-1]+' |'+' |'.join(str(s[k]) for k in ('tests','failures','errors','skipped')) for s in summary['suites'])
cov_rows='\n'.join('|'+key+' |'+str(cov[key]['covered'])+' |'+str(cov[key]['missed'])+' |'+str(cov[key]['percentage'])+'%' for key in ('LINE','BRANCH','INSTRUCTION','METHOD','CLASS'))
write('06-jacoco.adoc','统一权限管理系统：JaCoCo 与测试说明',f'''
== 本次实际结果

运行环境：本机 Java 21、Maven 3.9.16、Spring Boot 4.0.8、H2 内存数据库；JaCoCo 0.8.14。
测试源代码提交：`{summary['baseCommit']}`。完整命令为 `mvn -B clean verify`。
总计 {counts['tests']} 项，{passed} 项通过，失败 {counts['failures']}、错误 {counts['errors']}、跳过 {counts['skipped']}。
报告导出时间：{summary['exportedAt']}。

[cols="5,1,1,1,1",options="header"]
|===
|测试类 |总数 |失败 |错误 |跳过
{suite_rows}
|===

`ActualWorkbookImportTests` 依赖 `COMPANY_WORKBOOK_DIR` 指向外部三份 Excel。本机本轮未设置此变量，因此 1 项跳过；跳过不算通过。
公司导入的合成工作簿集成测试已执行。真实 Excel 测试的断言已按当前分表和初始改密策略更新，但本轮没有执行该条件测试。

== 覆盖率

[cols="2,1,1,1",options="header"]
|===
|指标 |已覆盖 |未覆盖 |比例
{cov_rows}
|===

覆盖率由本次 `jacoco.xml` 根节点计数计算，分母包含 JaCoCo 报告内所有生产类，未为提高数字配置覆盖排除。
行覆盖率衡量执行到的代码行，分支覆盖率衡量已走过的条件分支；不是需求完成比例，也不是负载测试结果。
新增模块的部分路径仍未覆盖，不能沿用旧版 85.91% 的覆盖率宣称当前版本达到相同水平。

== 报告文件与查看方式

* link:evidence/jacoco/index.html[JaCoCo HTML 首页]：下载或克隆仓库后用浏览器打开，Gitee 文件页可能只显示源码。
* link:evidence/jacoco/jacoco.xml[JaCoCo XML]、link:evidence/jacoco/jacoco.csv[CSV 统计]。
* link:evidence/surefire/[JUnit 测试类摘要]；link:evidence/test-summary.json[汇总、提交号与源码 SHA-256]。

[source,bash]
----
# 仓库根目录；先确认 Java 21 与 Maven 3.9+
java -version
mvn -version
mvn -B -f third-party/clonefactory/pom.xml -Dmaven.test.skip=true install
mvn -B clean verify
# 浏览器打开 target/site/jacoco/index.html
python3 scripts/export-evidence.py
python3 scripts/build-submission-docs.py
----

Windows 安装的 Python 若命令名为 `python`，相应替换最后两行。
单纯启动后端、登录网页或运行 JMeter，不会自动替代这条 Maven 测试命令。
`-DskipTests` 或 `-Dmaven.test.skip=true` 不可用于根项目的报告生成命令。
测试使用隔离 H2，不需要修改云端数据库，也不使用线上管理员凭证。

== 覆盖到的主要行为

* RBAC 权限并集、重叠角色撤回、管理 API 的匿名/普通用户拒绝、管理员字段防批量赋值。
* JWT 登录、刷新、退出、禁用/删除、密码变更、跨验证器撤销、登录和改密并发、事务回滚。
* 部门管理范围、初始密码一次发放、外部人员期限、锁定、空闲会话、权限点失效。
* HR 签名、离职和请求幂等；OA 登记、驳回及冲突；审计范围；导入幂等、回滚和 XML 外部实体拒绝。
* OIDC discovery 与静态页面可达；这不代表完整 OIDC 授权码流程均被自动测试覆盖。

本轮修正了测试中旧的 `sys_user.username/password_hash` 字段、旧令牌期望和共享软删除数据的清理方式。
运行中发现并修复两处实现问题：CLOB JSON 结果读取，以及已删除应用留下历史权限时列表映射报错。
测试保留越权、撤销、回滚和并发断言，没有通过删掉断言或排除生产类来提高通过率。

== 验证边界与历史记录

本次仅代表当前源码的 Maven 自动测试；未重做云端数据库迁移、生产部署、浏览器逐页回归、压力测试或外部 OA/HR 产品接入。
2026-10-22 的云端联调见 link:../phase-one/cloud-verification.json[历史记录]；不能与本轮 H2 测试混为一次验证。
`docs/first-check/evidence` 保留 2026-10-21 早期报告，*当前报告以本目录为准*。
后续于 2026-10-26 完成生产环境更新及在线检查，详见 link:07-deployment.adoc[部署记录]；部署检查不改变本节 Maven 测试范围。

重新导出必须先成功完成完整 `clean verify`。导出脚本不运行测试，不把文件存在当作测试成功。
源码 SHA-256 清单可用于核对报告对应代码；后续只改文档的提交不会改变这些摘要。
''')

write('README.adoc','统一权限管理系统：课程检查材料',f'''
本目录为当前提交材料入口；答辩复习 PDF 不纳入本次提交。

[cols="2,5",options="header"]
|===
|材料 |位置
|需求与实现 |link:01-requirements.adoc[01 需求与实现说明]；原始基线仍保留在 phase-one
|详细设计 |link:02-detailed-design.adoc[02 详细设计]：职责、对象关系、交互、事务、设计理由
|数据库设计 |link:03-database-design.adoc[03 数据库设计]：38 表字典、约束、索引与完整 DDL
|API 设计 |link:04-api-design.adoc[04 API 设计]：认证、签名、响应和 98 个操作
|界面设计 |link:05-ui-design.adoc[05 界面设计]：页面、身份范围、流程示意
|JaCoCo |link:06-jacoco.adoc[06 测试说明]；link:evidence/jacoco/index.html[完整 HTML 报告]
|部署更新 |link:07-deployment.adoc[2026-10-26 修复版本部署与在线核对]
|===

本次测试：{counts['tests']} 项，{passed} 通过，{counts['skipped']} 跳过，0 失败、0 错误；行覆盖率 {cov['LINE']['percentage']}%，分支覆盖率 {cov['BRANCH']['percentage']}%。
跳过原因为缺少外部 Excel 输入。报告对应提交与源码摘要见 link:evidence/test-summary.json[证据清单]。

代码目录为仓库根目录 `src` 与 `frontend`；四份历史 Word 保留在 link:../phase-one/README.md[一期材料目录]。
历史 Word 和早期测试保留原日期，当前检查请以本目录 `.adoc` 与报告为准。
仓库网页显示 HTML 源码时，下载仓库后本地打开 `docs/submission/evidence/jacoco/index.html`。

课程检查入口仍见 link:../phase-one/http-access.md[HTTP 访问说明]；账号密码不写入提交材料。
''')
print('Generated', len(list(D.glob('*.adoc'))), 'AsciiDoc files')
