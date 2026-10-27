"""Generate current API/ER design sources from offline exports, without running application tests."""
from pathlib import Path
import json,re
R=Path(__file__).resolve().parents[1];D=R/'docs/phase-one'
routes=json.loads((D/'api-routes.json').read_text())
previous=json.loads((D/'apifox/openapi.json').read_text())
paths={}
for route in routes:
 path,method=route['path'],route['method'].lower()
 old=previous.get('paths',{}).get(path,{}).get(method,{})
 op={'operationId':route['handler'].replace('.','_')+'_'+method+'_'+re.sub('[^a-zA-Z0-9]','_',path),'summary':route['handler'],'tags':[route['handler'].split('.')[0]],'parameters':route['parameters'],'responses':{'200':{'description':'成功；采用 v2 表示时数据位于 data 字段','content':{'application/json':{'schema':route['response']}}},'400':{'description':'参数不合法'},'401':{'description':'未认证或令牌需刷新'},'403':{'description':'无权限'},'409':{'description':'重复数据或状态冲突'}}}
 status=str(route['successStatus'])
 success={'description':'成功'}
 if status!='204':
  success['content']={'application/json':{'schema':route['response']},'application/vnd.cdwy.v2+json':{'schema':{'type':'object','required':['code','message','data'],'properties':{'code':{'type':'string','enum':['SUCCESS']},'message':{'type':'string'},'data':route['response']}}}}
 if path=='/api/company/imports/preview':
  op['parameters']=[p for p in op['parameters'] if p['name'] not in ('departments','employees','permissions')]
 op['responses'].pop('200');op['responses'][status]=success;op['responses']['404']={'description':'资源不存在'}
 if path!='/api/auth/login' and not path.startswith('/api/integration/'):op['security']=[{'bearerAuth':[]}]
 if 'request' in route:op['requestBody']={'required':True,'content':{'application/json':{'schema':route['request']}}}
 elif 'requestBody' in old:op['requestBody']=old['requestBody']
 if path.startswith('/api/integration/'):
  op['requestBody']={'required':True,'content':{'application/json':{'schema':{'$ref':'#/components/schemas/IntegrationEvent'}}}}
  op['description']='按 source、时间戳、请求编号和原始 JSON 字节计算 HMAC-SHA256。完整契约与动作见 api-design.md。'
 paths.setdefault(path,{})[method]=op
# Protocol endpoints keep the normative OAuth/OIDC shape rather than the platform business envelope.
for path,operations in previous.get('paths',{}).items():
 if not path.startswith('/api/'):paths[path]=operations
fields={'action':{'type':'string','enum':['DEPARTMENT','POST','JOIN','UPDATE','TRANSFER','LEAVE','SYNC','RECONCILE','APPLICATION','QUERY_PERMISSIONS','QUERY_APPLICATION_STATUS','RESULT']},'mode':{'type':'string','enum':['FULL','INCREMENTAL']},'externalId':{'type':'string'},'departmentExternalId':{'type':'string'},'postExternalId':{'type':'string'},'parentExternalId':{'type':'string'},'name':{'type':'string'},'displayName':{'type':'string'},'phone':{'type':'string'},'company':{'type':'string'},'username':{'type':'string','description':'兼容旧 HR 工号字段；不强制用作生成后的登录名'},'userId':{'type':'integer','format':'int64'},'departmentId':{'type':'integer','format':'int64'},'postId':{'type':'integer','format':'int64'},'roleIds':{'type':'array','items':{'type':'integer','format':'int64'}},'requestId':{'type':'string','description':'OA 业务申请单号，与签名 X-Request-Id 区分'},'approvalId':{'type':'integer','format':'int64'},'decision':{'type':'string','enum':['APPROVED','REJECTED','CANCELLED']},'records':{'type':'array','maxItems':20000,'items':{'$ref':'#/components/schemas/IntegrationEvent'}}}
components=previous.get('components',{});components.setdefault('schemas',{})['IntegrationEvent']={'type':'object','properties':fields}
components.setdefault('securitySchemes',{})['bearerAuth']={'type':'http','scheme':'bearer','bearerFormat':'JWT'}
components['schemas']['Envelope']={'type':'object','required':['code','message','data'],'properties':{'code':{'type':'string'},'message':{'type':'string'},'data':{'nullable':True}}}
spec={'openapi':'3.0.3','info':{'title':'统一权限管理系统一期接口','version':'1.4.2','description':'控制器接口由当前编译类离线导出。默认兼容旧响应；Accept: application/vnd.cdwy.v2+json 返回 code/message/data。OAuth/OIDC 协议端点不包装。本文件不是运行验收报告。'},'servers':[{'url':'http://120.46.213.158','description':'课程检查 HTTP 演示环境（传输不加密）'},{'url':'http://127.0.0.1:18081','description':'本地开发示例，实际部署替换'}],'paths':paths,'components':components}
(D/'apifox/openapi.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2))
text='''# 一期 API 设计说明

## 1 契约与版本

接口以原需求 DOC-01 第五、十一章为基准。管理与自助 API 使用 Bearer JWT，HR/OA 回调使用独立共享密钥的 HMAC-SHA256 签名，OIDC 使用标准授权码与 PKCE。

当前前端发送 Accept: application/vnd.cdwy.v2+json，普通 JSON 响应包含 code、message、data。成功码 SUCCESS；参数、认证、授权、资源和冲突分别使用 INVALID_ARGUMENT、UNAUTHENTICATED、FORBIDDEN、NOT_FOUND、CONFLICT。HTTP 仍表达相应协议状态。204 无响应体。未发送版本头的旧客户端保持旧表示；OAuth/OIDC 端点维持协议原有格式。OpenAPI 分别描述 application/json 原始响应和 application/vnd.cdwy.v2+json 包装响应，创建资源按201、无响应体按204记录。

```json
{"code":"SUCCESS","message":"成功","data":{"items":[],"total":0}}
```

管理接口在服务端复核角色、目标用户、部门范围和委派上限。前端导航权限不能替代后端检查。列表采用 page 从零开始、size 默认25且最多100；个别目录接口上限500，批次查询或摘要列表的上限见代码参数说明。

## 2 认证和权限变更

POST /api/auth/login 接收 username 和 password，账号或唯一手机号均可登录。平台 JWT 携带 permission_version；每次使用比较当前版本。版本改变时旧 JWT 返回401，前端可 POST /api/auth/refresh 换取当前权限版本令牌，无需重新输入密码。禁用、离职、改密或强制下线会撤销数据库会话，刷新也失败。

OIDC 端点遵循协议，不套业务响应。授权码客户端必须使用 S256 PKCE；精确匹配登记的回调；公开客户端不发放刷新令牌。机密客户端使用 client_secret_basic 且启用刷新令牌轮换。应用配置改变后进入 DRAFT 并撤销旧授权；完成接入验收后才启用。

## 3 应用配置与验收

先 POST /api/systems 注册目录，再 PUT /api/platform/applications/{id}/config。输入 clientId、entryUrl、protocol、redirectUris、clientSecret、compatibilityPlan。客户端编号不可更改；配置读取不返回密钥。外部地址要求 HTTPS，回环演示允许 HTTP。LEGACY 必须说明隔离方案，并明确没有统一免登能力。

POST /api/platform/applications/{id}/acceptance 接收 login、callback、permission、revocation、isolation 和 evidence。OIDC 五项均需确认；LEGACY 至少确认隔离。此接口保存人工验收结果，不自动执行测试。GET /api/platform/self/portal 只返回已验收、启用且当前用户获授权的入口。

## 4 HR 与 OA 签名格式

POST /api/integration/{source}，source 为 hr 或 oa。X-Request-Id 为请求幂等编号，X-Timestamp 为 Unix 秒，时间窗口为五分钟。签名为以下 UTF-8 原文的 HMAC-SHA256 十六进制摘要，不对 JSON 重新排版：

```text
source + "\\n" + timestamp + "\\n" + requestId + "\\n" + rawBody
```

HR 与 OA 密钥必须不同且至少32字符，通过 PERMISSION_HR_SECRET 与 PERMISSION_OA_SECRET 配置。请求体最多1200万字符。相同编号相同内容返回历史结果，不重复变更；相同编号不同内容409。业务事务失败后，单独保存请求摘要和错误原因；原始数据修正后必须使用新编号。没有验签通过的请求不会进入同步异常库。

### 4.1 HR 动作

| 动作 | 输入要点 | 行为 |
| --- | --- | --- |
| DEPARTMENT | externalId name parentExternalId | 先父后子同步，禁止部门环 |
| POST | externalId name | 同步岗位及同名角色，不覆盖既有授权 |
| JOIN | externalId displayName departmentExternalId postExternalId phone company | 创建拼音账号、随机密码和初始改密策略，不授予角色；已有稳定编号只更新未变更组织的资料 |
| UPDATE | externalId 或 userId，加完整资料 | 更新姓名、电话和单位，组织不同则要求 TRANSFER |
| TRANSFER | 员工编号、新组织和完整 roleIds | 单事务回收旧授权并分配新岗位角色，写两部门通知 |
| LEAVE | externalId 或 userId | 停用、撤权、撤销会话 |
| SYNC | mode FULL 或 INCREMENTAL，records | 最多20000条，依赖有序、整批原子处理；不因遗漏自动删除员工 |
| RECONCILE | records 中提供 externalId | 返回 missing extra matched，不修改员工 |

```json
{"action":"SYNC","mode":"INCREMENTAL","records":[{"action":"DEPARTMENT","externalId":"dept-it","name":"信息中心"},{"action":"POST","externalId":"post-dev","name":"开发岗"},{"action":"JOIN","externalId":"E1001","displayName":"张三","departmentExternalId":"dept-it","postExternalId":"post-dev","phone":"13800000001","company":"示例公司"}]}
```

### 4.2 OA 动作

APPLICATION 接收 OA requestId、userId、单元素 roleIds，登记待审批申请；申请在 OA 发起。旧 POST /api/platform/self/applications 已删除，GET 本人状态查询保留。QUERY_PERMISSIONS 按 userId 查询当前权限；QUERY_APPLICATION_STATUS 按 requestId 查询状态。RESULT 按 requestId 或兼容 approvalId 指定申请，decision 为 APPROVED、REJECTED、CANCELLED。已处理申请的相同结论返回成功，不同结论409。批准前检查申请人有效状态，拒绝和取消不授予权限。

```json
{"action":"APPLICATION","requestId":"OA-2026-001","userId":12,"roleIds":[7]}
```

```json
{"action":"RESULT","requestId":"OA-2026-001","decision":"APPROVED"}
```

## 5 复核 审计与组织管理

GET /api/platform/reviews/candidates 返回受管员工角色；POST /api/platform/reviews 接收 userId、roleId、KEEP 或 REVOKE、reason。回收与记录在一个事务中完成。GET /reviews 查看历史，禁止普通用户查询。

GET /api/platform/audit/search 接收 from、until、actorId、kind、archived、page、size；时间区间为左闭右开。归档与在线查询使用相同管理范围。归档任务只移动超过十二个月的记录，不提供日志写改删 API。

同步异常通过 /api/platform/sync/failures 查看，处理记录通过 /failures/{id}/resolve 写入。标记已处理不能代替 HR 重发成功；实际同步结果仍在 integration_event 中记录。部门通知包括调岗交接、账号命名人工核对、外部账号到期提醒。

管理员成员、组织维护及用户资料使用独立接口，校验目标账号和部门引用。首次内置管理员不能变更身份，管理员不能修改自己的超级管理员成员状态。

## 6 协议适配能力

ApplicationProtocol 表示协议能力，OIDC 是一期唯一 SSO 实现；LEGACY 只提供隔离入口。OAuth2 独立场景、SAML、CAS、LDAP 仅保留扩展边界，不能标为已经实现。应用接入验收必须包含正向登录、错误回调、无权限、撤权后拒绝及隔离检查。

## 7 控制器路由清单

以下清单由当前编译类离线导出。接口存在和编译通过不等于运行场景验收通过。

| 方法 | 路径 | 处理入口 |
| --- | --- | --- |
'''
for route in routes:text+=f"| {route['method']} | {route['path']} | {route['handler']} |\n"
(D/'api-design.md').write_text(text)
# ER relationships come from actual DDL foreign keys, not inferred logical references.
sql=(D/'database-mysql-phase-one.sql').read_text();tables=re.findall(r'create table(?: if not exists)?\s+([A-Za-z_][A-Za-z0-9_]*)\s*\((.*?)\)\s*(?:engine[^;]*)?;',sql,re.I|re.S)
# Generate ER cardinalities and the catalog from the same physical DDL.
import runpy
runpy.run_path(str(R/'scripts/build-er-assets.py'), run_name='__main__')
print('API operations',len(routes),'tables',len(tables))
