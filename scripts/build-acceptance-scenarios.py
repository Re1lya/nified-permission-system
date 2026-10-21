"""Build the phase-one acceptance scenarios as a Postman v2.1 collection (Apifox and newman both import it).
Credentials and integration secrets are never written here; pass them as environment variables at run time.
"""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/phase-one/apifox/phase-one-scenarios.postman_collection.json'

def req(name,method,path,token=None,body=None,tests=(),pre=(),form=None,headers=()):
    h=[{'key':k,'value':v} for k,v in headers]
    if token:h.append({'key':'Authorization','value':'Bearer {{'+token+'}}'})
    r={'method':method,'header':h,'url':{'raw':'{{baseUrl}}'+path,'host':['{{baseUrl}}'],'path':[p for p in path.split('?')[0].split('/') if p]}}
    if '?' in path:r['url']['query']=[dict(zip(('key','value'),q.split('='))) for q in path.split('?')[1].split('&')]
    if body is not None:
        h.append({'key':'Content-Type','value':'application/json'})
        r['body']={'mode':'raw','raw':body if isinstance(body,str) else json.dumps(body,ensure_ascii=False)}
    if form:r['body']={'mode':'formdata','formdata':[{'key':k,'type':'file','src':v} for k,v in form]}
    ev=[]
    if pre:ev.append({'listen':'prerequest','script':{'type':'text/javascript','exec':list(pre)}})
    if tests:ev.append({'listen':'test','script':{'type':'text/javascript','exec':list(tests)}})
    return {'name':name,'request':r,'event':ev}

def status(code,label):return f"pm.test('{label}', () => pm.response.to.have.status({code}));"
def status_in(codes,label):return f"pm.test('{label}', () => pm.expect({list(codes)}).to.include(pm.code()));".replace('pm.code()','pm.response.code')
def save(var,expr):return f"pm.collectionVariables.set('{var}', {expr});"

def signed(source,request_id,body_js,secret_var='hrSecret',stale=False,bad=False):
    """Pre-request script: build the exact raw body, then sign source\\ntimestamp\\nrequestId\\nbody with HMAC-SHA256."""
    ts='Math.floor(Date.now()/1000)'+(' - 900' if stale else '')
    sig="'00'.repeat(32)" if bad else f"CryptoJS.HmacSHA256('{source}\\n' + ts + '\\n' + id + '\\n' + body, pm.environment.get('{secret_var}') || pm.variables.get('{secret_var}')).toString()"
    return [f"const id = {request_id};",f"const body = JSON.stringify({body_js});",f"const ts = String({ts});",
            "pm.variables.set('rawBody', body); pm.variables.set('reqId', id); pm.variables.set('ts', ts);",f"pm.variables.set('sig', {sig});"]

def integration(name,source,request_id,body_js,tests,**kw):
    secret='hrSecret' if source=='hr' else 'oaSecret'
    return req(name,'POST','/api/integration/'+source,body='{{rawBody}}',pre=signed(source,request_id,body_js,secret,**kw),tests=tests,
               headers=[('X-Request-Id','{{reqId}}'),('X-Timestamp','{{ts}}'),('X-Signature','{{sig}}')])

run="pm.collectionVariables.get('runId')"
folders=[
 ('AUTH-01 登录与密码策略',[
  req('管理员登录','POST','/api/auth/login',body={'username':'{{adminUser}}','password':'{{adminPassword}}'},
      pre=["if (!pm.collectionVariables.get('runId')) pm.collectionVariables.set('runId', Date.now().toString(36));"],
      tests=[status(200,'AUTH-01 管理员登录成功'),"const t = pm.response.json().accessToken;","pm.test('返回访问令牌', () => pm.expect(t).to.be.a('string').and.not.empty);",save('adminToken','t')]),
  req('错误密码登录（反向）','POST','/api/auth/login',body={'username':'{{adminUser}}','password':'Wrong-Password-1'},
      tests=[status(401,'AUTH-01 错误密码返回 401'),"pm.test('不返回令牌', () => pm.expect(pm.response.text()).to.not.include('accessToken'));"]),
 ]),
 ('RBAC-01 准备：两个角色共享 expense:list',[
  req('创建业务系统','POST','/api/systems','adminToken',{'code':'demo_sys_{{runId}}','name':'演示报销系统','baseUrl':'http://127.0.0.1:18101'},
      tests=[status(201,'创建系统返回 201'),save('systemId','pm.response.json().id')]),
  req('创建权限 expense:list','POST','/api/permissions','adminToken',{'systemId':'{{systemId}}','code':'expense:list','name':'查看报销','menuPath':'/expenses'},
      tests=[status_in([201,409],'创建权限返回 201 或已存在 409'),"if (pm.response.code === 201) "+save('permId','pm.response.json().id')]),
  req('查询权限编号','GET','/api/permissions','adminToken',
      tests=[status(200,'权限列表可读'),"const p = pm.response.json().find(x => x.code === 'expense:list');","pm.test('expense:list 存在', () => pm.expect(p).to.be.an('object'));",save('permId','p.id')]),
  req('创建角色 A','POST','/api/roles','adminToken',{'code':'demo_a_{{runId}}','name':'演示角色A','description':'Apifox 验收'},
      tests=[status(201,'创建角色 A 返回 201'),save('roleA','pm.response.json().id')]),
  req('创建角色 B','POST','/api/roles','adminToken',{'code':'demo_b_{{runId}}','name':'演示角色B','description':'Apifox 验收'},
      tests=[status(201,'创建角色 B 返回 201'),save('roleB','pm.response.json().id')]),
  req('角色 A 授予 expense:list','PUT','/api/roles/{{roleA}}/permissions/{{permId}}','adminToken',tests=[status_in([200,204],'角色 A 授权成功')]),
  req('角色 B 授予 expense:list','PUT','/api/roles/{{roleB}}/permissions/{{permId}}','adminToken',tests=[status_in([200,204],'角色 B 授权成功')]),
  req('创建演示用户','POST','/api/users','adminToken',{'username':'demo_u_{{runId}}','displayName':'演示用户'},
      tests=[status(201,'创建用户返回 201'),save('userId','pm.response.json().id')]),
  req('设置演示用户密码','PUT','/api/users/{{userId}}/password','adminToken',{'password':'Demo-{{runId}}-9a'},tests=[status_in([200,204],'设置密码成功')]),
  req('赋予角色 A','PUT','/api/users/{{userId}}/roles/{{roleA}}','adminToken',tests=[status_in([200,204],'赋予角色 A')]),
  req('赋予角色 B','PUT','/api/users/{{userId}}/roles/{{roleB}}','adminToken',tests=[status_in([200,204],'赋予角色 B')]),
  req('演示用户登录','POST','/api/auth/login',body={'username':'demo_u_{{runId}}','password':'Demo-{{runId}}-9a'},
      tests=[status(200,'演示用户登录成功'),save('userToken','pm.response.json().accessToken'),
             "pm.test('有效权限含 expense:list', () => pm.expect(JSON.stringify(pm.response.json().permissions)).to.include('expense:list'));"]),
 ]),
 ('SCOPE-01 越权与无效令牌（反向）',[
  req('普通用户请求管理 API','GET','/api/users','userToken',tests=[status(403,'SCOPE-01 普通用户管理 API 返回 403')]),
  req('普通用户请求导入预览列表','GET','/api/company/imports','userToken',tests=[status(403,'SCOPE-01 普通用户不能读导入批次')]),
  req('未携带令牌','GET','/api/demo/expenses',tests=[status(401,'未登录返回 401')]),
  req('伪造或过期令牌','GET','/api/demo/expenses',headers=[('Authorization','Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.invalid')],tests=[status(401,'无效令牌返回 401')]),
 ]),
 ('RBAC-01 权限并集与回收',[
  req('两角色时访问报销','GET','/api/demo/expenses','userToken',tests=[status(200,'RBAC-01 持有两角色可访问')]),
  req('回收角色 A','DELETE','/api/users/{{userId}}/roles/{{roleA}}','adminToken',tests=[status_in([200,204],'回收角色 A')]),
  req('同一令牌仍可访问','GET','/api/demo/expenses','userToken',tests=[status(200,'RBAC-01 角色 B 仍提供权限，返回 200')]),
  req('回收角色 B','DELETE','/api/users/{{userId}}/roles/{{roleB}}','adminToken',tests=[status_in([200,204],'回收角色 B')]),
  req('同一令牌被拒绝','GET','/api/demo/expenses','userToken',tests=[status(403,'RBAC-01 全部来源回收后返回 403')]),
 ]),
 ('SESSION-01 注销后旧令牌失效',[
  req('用户注销','POST','/api/auth/logout','userToken',tests=[status_in([200,204],'注销成功')]),
  req('旧令牌再次访问','GET','/api/demo/expenses','userToken',tests=[status(401,'SESSION-01 注销后旧令牌返回 401')]),
 ]),
 ('IMPORT-01 三表预览、提交与幂等',[
  req('三表预览','POST','/api/company/imports/preview','adminToken',form=[('departments','{{workbookDir}}/部门演示.xlsx'),('employees','{{workbookDir}}/员工演示.xlsx'),('permissions','{{workbookDir}}/权限演示.xlsx')],
      tests=[status(200,'预览成功'),"const b = pm.response.json();","pm.test('IMPORT-01 预览无错误', () => pm.expect(b.errorCount).to.eql(0));",save('previewId','b.previewId')]),
  req('确认提交','POST','/api/company/imports/commit','adminToken',{'previewId':'{{previewId}}','acknowledgeWarnings':True},
      tests=[status(200,'提交成功'),"const c = pm.response.json();","pm.test('新建批次或识别为已导入', () => pm.expect(typeof c.batchId === 'number' || c.reused === true).to.eql(true));"]),
  req('同一三表再次预览','POST','/api/company/imports/preview','adminToken',form=[('departments','{{workbookDir}}/部门演示.xlsx'),('employees','{{workbookDir}}/员工演示.xlsx'),('permissions','{{workbookDir}}/权限演示.xlsx')],
      tests=[status(200,'再次预览成功'),save('previewId','pm.response.json().previewId')]),
  req('重复提交','POST','/api/company/imports/commit','adminToken',{'previewId':'{{previewId}}','acknowledgeWarnings':True},
      tests=[status(200,'重复提交返回 200'),"pm.test('IMPORT-01 重复提交 reused=true', () => pm.expect(pm.response.json().reused).to.eql(true));"]),
  req('读取部门','GET','/api/company/departments','adminToken',tests=[status(200,'部门列表'),save('departmentId','pm.response.json()[0].id')]),
  req('读取岗位','GET','/api/company/posts','adminToken',tests=[status(200,'岗位列表'),"const p = pm.response.json()[0];",save('postId','p.id'),save('postRoleId','p.role_id')]),
 ]),
 ('HR-01 签名事件与幂等',[
  integration('入职事件','hr',f"'hr_' + {run}","{action:'JOIN',username:'hr_'+"+run+",displayName:'演示入职',departmentId:Number(pm.collectionVariables.get('departmentId')),postId:Number(pm.collectionVariables.get('postId')),phone:'13900000000',company:'演示公司'}",
      [status(200,'HR-01 入职成功'),"pm.test('首次处理 reused=false', () => pm.expect(pm.response.json().reused).to.eql(false));"]),
  integration('同一事件重发','hr',f"'hr_' + {run}","{action:'JOIN',username:'hr_'+"+run+",displayName:'演示入职',departmentId:Number(pm.collectionVariables.get('departmentId')),postId:Number(pm.collectionVariables.get('postId')),phone:'13900000000',company:'演示公司'}",
      [status(200,'重发返回 200'),"pm.test('HR-01 重发 reused=true', () => pm.expect(pm.response.json().reused).to.eql(true));"]),
  integration('同编号不同内容（反向）','hr',f"'hr_' + {run}","{action:'JOIN',username:'hr_x_'+"+run+",displayName:'改动内容',departmentId:Number(pm.collectionVariables.get('departmentId')),postId:Number(pm.collectionVariables.get('postId')),phone:'13900000001',company:'演示公司'}",
      [status(409,'HR-01 同编号不同内容返回 409')]),
  integration('过期时间戳（反向）','hr',f"'hr_old_' + {run}","{action:'JOIN',username:'hr_old_'+"+run+",displayName:'过期',departmentId:1,postId:1,phone:'13900000002',company:'演示公司'}",
      [status(403,'过期签名返回 403')],stale=True),
 ]),
 ('OA-01 申请与审批回调',[
  req('演示用户重新登录','POST','/api/auth/login',body={'username':'demo_u_{{runId}}','password':'Demo-{{runId}}-9a'},tests=[status(200,'重新登录'),save('userToken','pm.response.json().accessToken')]),
  req('提交岗位角色申请','POST','/api/platform/self/applications','userToken',{'roleId':'{{postRoleId}}'},
      tests=[status(200,'申请已登记'),"const a = pm.response.json();","pm.test('状态为 PENDING', () => pm.expect(a.decision).to.eql('PENDING'));",save('approvalId','a.id')]),
  integration('错误签名回调（反向）','oa',f"'oa_bad_' + {run}","{approvalId:Number(pm.collectionVariables.get('approvalId')),decision:'APPROVED'}",[status(403,'错误签名返回 403')],bad=True),
  integration('审批通过回调','oa',f"'oa_' + {run}","{approvalId:Number(pm.collectionVariables.get('approvalId')),decision:'APPROVED'}",
      [status(200,'OA-01 回调成功'),"pm.test('首次处理 reused=false', () => pm.expect(pm.response.json().reused).to.eql(false));"]),
  integration('重复回调','oa',f"'oa_' + {run}","{approvalId:Number(pm.collectionVariables.get('approvalId')),decision:'APPROVED'}",
      [status(200,'重复回调返回 200'),"pm.test('OA-01 重复回调 reused=true', () => pm.expect(pm.response.json().reused).to.eql(true));"]),
  req('查询本人申请','GET','/api/platform/self/applications','userToken',
      tests=[status(200,'申请列表'),"const a = pm.response.json().find(x => x.id == pm.collectionVariables.get('approvalId'));","pm.test('OA-01 申请变为 APPROVED', () => pm.expect(a.decision).to.eql('APPROVED'));"]),
  req('已授予申请角色','GET','/api/platform/self','userToken',tests=[status(200,'本人信息'),"pm.test('权限版本已增加', () => pm.expect(pm.response.json().version).to.be.above(0));"]),
 ]),
 ('EXT-01 外部与临时人员有效期',[
  req('计算日期','GET','/api/platform/self','adminToken',pre=["const d = n => { const t = new Date(Date.now() + 8*3600e3 + n*86400e3); return t.toISOString().slice(0,10); };","['3','30','91'].forEach(n => pm.collectionVariables.set('day'+n, d(Number(n))));"],tests=[status(200,'管理员身份有效')]),
  req('超过 90 天的临时人员（反向）','POST','/api/platform/external-users','adminToken',{'username':'tmp_{{runId}}','displayName':'临时人员','personType':'TEMPORARY','expiresOn':'{{day91}}','departmentId':'{{departmentId}}','postId':'{{postId}}','company':'演示外包公司','phone':'13700000000','password':'Ext-{{runId}}-9a'},
      tests=[status(400,'EXT-01 临时人员超过 90 天返回 400')]),
  req('创建 3 天后到期的临时人员','POST','/api/platform/external-users','adminToken',{'username':'tmp_{{runId}}','displayName':'临时人员','personType':'TEMPORARY','expiresOn':'{{day3}}','departmentId':'{{departmentId}}','postId':'{{postId}}','company':'演示外包公司','phone':'13700000000','password':'Ext-{{runId}}-9a'},
      tests=[status(200,'EXT-01 创建成功'),save('externalId','pm.response.json().id')]),
  req('到期提醒列表','GET','/api/platform/external-users','adminToken',
      tests=[status(200,'列表可读'),"const r = pm.response.json().find(x => x.id == pm.collectionVariables.get('externalId'));","pm.test('EXT-01 标为即将到期', () => pm.expect(r.status).to.eql('EXPIRING'));","pm.test('剩余 3 天', () => pm.expect(r.days_left).to.eql(3));"]),
  req('普通用户不能查看（反向）','GET','/api/platform/external-users','userToken',tests=[status(403,'EXT-01 普通用户返回 403')]),
  req('续期到 30 天后','PUT','/api/platform/external-users/{{externalId}}/expiry','adminToken',{'expiresOn':'{{day30}}'},tests=[status(200,'EXT-01 续期成功')]),
  req('临时人员登录','POST','/api/auth/login',body={'username':'tmp_{{runId}}','password':'Ext-{{runId}}-9a'},tests=[status(200,'EXT-01 有效期内可登录')]),
 ]),
]
collection={'info':{'name':'统一权限管理 一期验收串联场景','schema':'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  'description':'对应 checks.md 的 9 个场景及 EXT-01 外部人员有效期。运行时提供 baseUrl、adminUser、adminPassword、hrSecret、oaSecret、workbookDir；不要把真实凭据写入集合。'},
  'variable':[{'key':'runId','value':''}],'item':[{'name':n,'item':items} for n,items in folders]}
OUT.write_text(json.dumps(collection,ensure_ascii=False,indent=2),encoding='utf-8')
print(OUT.name,sum(len(i) for _,i in folders),'requests')
