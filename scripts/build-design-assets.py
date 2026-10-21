"""Build editable ER diagrams and an Apifox-importable OpenAPI contract."""
from pathlib import Path
import json,re,math
from PIL import Image,ImageDraw,ImageFont
R=Path(__file__).resolve().parents[1];D=R/'docs/phase-one';A=D/'apifox';A.mkdir(exist_ok=True)
routes=json.loads((R/'output/phase-one-endpoints.json').read_text(encoding='utf-8'))
schema=json.loads((R/'output/phase-one-schema.json').read_text(encoding='utf-8'))
raw=(R/'output/phase-one-h2.sql').read_text(encoding='utf-8')
fks=[tuple(x.lower() for x in m) for m in re.findall(r'ALTER TABLE "PUBLIC"\."(\w+)" ADD CONSTRAINT .*? FOREIGN KEY\("(\w+)"\) REFERENCES "PUBLIC"\."(\w+)"\("(\w+)"\)',raw)]
unique=set((t.lower(),c.lower()) for t,c in re.findall(r'ALTER TABLE "PUBLIC"\."(\w+)" ADD CONSTRAINT [^;]+? UNIQUE(?: NULLS DISTINCT)?\s*\("(\w+)"\)',raw))
mm=['erDiagram']
for name,cols in schema.items():
 mm.append('  '+name+' {')
 for c in cols:
  n=c['COLUMN_NAME'].lower();ty='string' if c['CHARACTER_MAXIMUM_LENGTH'] else c['DATA_TYPE'].lower().split()[0]
  mark=' PK' if n=='id' else (' FK' if any(t==name and f==n for t,f,_,_ in fks) else '')
  mm.append(f'    {ty} {n}{mark}')
 mm.append('  }')
for child,col,parent,pk in fks:
 nullable=next(c['IS_NULLABLE']=='YES' for c in schema[child] if c['COLUMN_NAME'].lower()==col)
 mm.append(f'  {parent} '+('|o' if nullable else '||')+'--'+('o|' if (child,col) in unique else 'o{')+f' {child} : "{col}"')
(D/'database-er.mmd').write_text('\n'.join(mm)+'\n',encoding='utf-8')

# Readable group diagrams: actual table boxes plus an explicit FK relation ledger.
groups=[('rbac','RBAC0 授权与会话',['sys_user','sys_user_role','sys_role','sys_role_permission','sys_permission','biz_system','sys_login_session']),('company','组织、员工与功能目录',['sys_department','employee_profile','sys_post','sys_module','sys_function','permission_detail','permission_template','company_import_batch']),('platform','范围管理、账号策略与集成',['admin_department','managed_role','department_permission','account_policy','session_activity','approval_record','integration_event','audit_event','login_attempt','data_dictionary'])]
font='C:/Windows/Fonts/msyh.ttc';F=lambda size:ImageFont.truetype(font,size)
for key,title,names in groups:
 edges=[x for x in fks if x[0] in names]
 height=170+max(len(edges),1)*125
 im=Image.new('RGB',(1500,height),'#f7faf9');draw=ImageDraw.Draw(im)
 draw.text((45,25),title,font=F(34),fill='#164c3f')
 draw.text((45,80),'按关系展开实体；同名方框表示同一张表。完整字段见数据字典。',font=F(22),fill='#344b45')
 for i,(child,col,parent,pk) in enumerate(edges):
  y=135+i*125
  for x,name,field in [(45,parent,pk),(930,child,col)]:
   draw.rounded_rectangle((x,y,x+525,y+90),10,fill='white',outline='#217561',width=2)
   draw.text((x+16,y+10),name,font=F(25),fill='#164c3f')
   draw.text((x+16,y+50),field,font=F(21),fill='#344b45')
  draw.line((570,y+45,930,y+45),fill='#217561',width=3)
  draw.line((590,y+31,590,y+59),fill='#217561',width=3)
  if (child,col) not in unique:
   draw.line((910,y+45,930,y+29),fill='#217561',width=3)
   draw.line((910,y+45,930,y+61),fill='#217561',width=3)
  else:draw.line((910,y+31,910,y+59),fill='#217561',width=3)
  draw.text((650,y+5),'1 : 0..1' if (child,col) in unique else '1 : N',font=F(22),fill='#164c3f')
 im.save(D/'images'/f'er-{key}.png')

def typ(t):
 if t in ('Long','long','Integer','int'):return {'type':'integer','format':'int64' if t.lower()=='long' else 'int32'}
 if t in ('Boolean','boolean'):return {'type':'boolean'}
 if t.startswith('List') or t.endswith('[]'):return {'type':'array','items':{'type':'integer','format':'int64'}}
 return {'type':'string'}
spec={'openapi':'3.0.3','info':{'title':'统一权限管理系统 · 一期','version':'1.1.0','description':'与当前 Controller 和需求 Spec 对应。平台 JWT、OIDC Bearer 与 OA/HR 签名分别定义。示例采用虚构数据。'},'servers':[{'url':'http://127.0.0.1:18081','description':'本地 H2 演示'}],'security':[{'PlatformBearer':[]}],'paths':{},'components':{'securitySchemes':{'PlatformBearer':{'type':'http','scheme':'bearer','bearerFormat':'JWT'},'OidcBearer':{'type':'http','scheme':'bearer','bearerFormat':'JWT'},'IntegrationSignature':{'type':'apiKey','in':'header','name':'X-Signature'}},'schemas':{}}}
sources='\n'.join(p.read_text(encoding='utf-8') for p in (R/'src/main/java').rglob('*.java'))
for e in routes:
 path=e['path']
 if not path.startswith('/api/'):continue
 tag=path.split('/')[2];params=[];files=[];body=None
 for n in re.findall(r'\{(\w+)\}',path):params.append({'name':n,'in':'path','required':True,'schema':{'type':'string'} if n in ['source','session'] else {'type':'integer','format':'int64'}})
 for q in e['parameters']:
  if q['type']=='MultipartFile':files.append(q['name']);continue
  if q['kind']=='query':
   s=typ(q['type']);default=q.get('default','');required=q.get('required',False) and not default and q['name'] not in ('q','kind')
   if default:s['default']=int(default) if s['type']=='integer' else default
   params.append({'name':q['name'],'in':'query','required':required,'schema':s})
  elif q['kind']=='body':body=q
 for method in e['methods']:
  op={'tags':[tag],'summary':e['handler']+' · '+path,'operationId':method.lower()+'_'+re.sub(r'\W+','_',path).strip('_'),'parameters':params.copy(),'responses':{'200':{'description':'操作成功；响应字段见对应业务契约','content':{'application/json':{'schema':{}}}},'400':{'description':'输入校验失败'},'401':{'description':'登录、令牌或会话失效'},'403':{'description':'身份、管理范围或业务权限不足'},'409':{'description':'业务键、引用或幂等内容冲突'}}}
  if path=='/api/auth/login':op['security']=[]
  if path.startswith('/api/oidc'):op['security']=[{'OidcBearer':[]}]
  if path.startswith('/api/integration'):
   op['security']=[{'IntegrationSignature':[]}]
   op['parameters'] += [{'name':n,'in':'header','required':True,'schema':{'type':'string'},'description':desc} for n,desc in [('X-Request-Id','稳定事件编号，1..64 位字母数字下划线连字符'),('X-Timestamp','Unix 秒，允许时差 300 秒')]]
   op['description']='HMAC-SHA256(source + 换行 + timestamp + 换行 + requestId + 换行 + 原始JSON)。hr 与 oa 分别使用环境密钥。'
   fields={n:typ(t) for n,t in [('action','String'),('username','String'),('displayName','String'),('userId','Long'),('departmentId','Long'),('postId','Long'),('phone','String'),('company','String'),('roleIds','List'),('approvalId','Long'),('decision','String')]}
   op['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','properties':fields},'examples':{'入职':{'value':{'action':'JOIN','username':'demo_new','displayName':'演示员工','departmentId':1,'postId':1,'phone':'13900000000','company':'演示公司'}},'审批':{'value':{'approvalId':1,'decision':'APPROVED'}}}}}}
  elif body:
   fields={f['name']:typ(f['type']) for f in body['fields']};model={'type':'object','properties':fields}
   match=re.search(r'record '+re.escape(body['type'])+r'\((.*?)\)\s*\{',sources,re.S)
   source=match[1] if match else ''
   if not source:
    file=next((R/'src/main/java').rglob(body['type']+'.java'),None)
    if file:source=file.read_text(encoding='utf-8')
   required=[]
   for n,field in fields.items():
    m=re.search(r'((?:@\w+(?:\([^)]*\))?\s*)+)(?:private\s+)?[\w<>]+\s+'+re.escape(n)+r'\b',source)
    if m:
     if re.search(r'@(NotBlank|NotNull|Positive)\b',m[1]):required.append(n)
     for k,val in re.findall(r'(min|max)\s*=\s*(\d+)',m[1]):field[('minLength' if k=='min' else 'maxLength') if field['type']=='string' else ('minItems' if k=='min' else 'maxItems')]=int(val)
     if '@Positive' in m[1]:field['minimum']=1
   if required:model['required']=required
   spec['components']['schemas'][body['type']]=model
   op['requestBody']={'required':True,'content':{'application/json':{'schema':{'$ref':'#/components/schemas/'+body['type']}}}}
  if files:op['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':{'type':'object','required':files,'properties':{n:{'type':'string','format':'binary'} for n in files}}}}}
  if path=='/api/auth/login':
   op['responses']['200']['content']['application/json']['schema']={'type':'object','required':['accessToken','userId','username','displayName','permissions'],'properties':{'accessToken':{'type':'string'},'userId':{'type':'integer','format':'int64'},'username':{'type':'string'},'displayName':{'type':'string'},'permissions':{'type':'array','items':{'type':'object'}}}}
  if path=='/api/auth/logout':op['responses']={'204':{'description':'注销成功，无响应体'},'401':{'description':'会话无效'}}
  root=path.split('/')[2]
  if root in ('users','roles','systems','permissions'):
   if method=='POST':op['responses']['201']=op['responses'].pop('200')
   if method=='DELETE' or (method=='PUT' and ('/password' in path or '/roles/' in path and root=='users' or '/permissions/' in path and root=='roles')):
    op['responses'].pop('200',None);op['responses']['204']={'description':'操作成功，无响应体'}
  for code in ('400','401','403','409'):
   if code in op['responses']:op['responses'][code]['content']={'application/json':{'schema':{'type':'object','properties':{'error':{'type':'string'},'message':{'type':'string'}}}}}
  spec['paths'].setdefault(path,{})[method.lower()]=op
(A/'openapi.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
(A/'checks.md').write_text('''# Apifox 检查场景

导入 openapi.json 后，在测试场景中从接口添加步骤。baseUrl 为本地 18081，管理员口令只保存在本地私密环境。每轮使用唯一的 demo_ 前缀，保存本轮创建的用户、角色及权限编号。不要导入真实员工资料和运行密钥。

| 场景 / Spec | 步骤 | 断言 |
| --- | --- | --- |
| AUTH-01 登录 | POST /api/auth/login | 200；accessToken 非空；保存 adminToken |
| SCOPE-01 普通用户越权 | 普通用户 JWT 请求 GET /api/users | 403；保持登录 |
| RBAC-01 权限并集 | 创建两个角色，共同拥有 expense:list；赋给演示用户；用户访问报销 | 200 |
| RBAC-01 回收部分 | 管理员回收一个角色；使用同一个 userToken 访问报销 | 200 |
| RBAC-01 回收全部 | 回收第二角色；继续使用原 userToken | 403 |
| SESSION-01 注销 | 用户 POST /api/auth/logout，再使用原 userToken 请求报销 | 204，然后 401 |
| IMPORT-01 导入 | 三个虚构 XLSX 预览、确认提交、相同文件再提交 | errors 为空；重复提交 reused=true |
| HR-01 幂等 | 同一签名事件重发；同编号改变内容重新签名 | 首次 reused=false，重发 true，冲突 409 |
| OA-01 审批 | 用户创建申请；签名 APPROVED 回调 | 申请由 PENDING 变为 APPROVED；授予申请内角色 |

登录接口后置脚本：
```javascript
pm.test('登录成功', () => pm.response.to.have.status(200));
const data = pm.response.json();
pm.test('返回访问令牌', () => pm.expect(data.accessToken).to.be.a('string').and.not.empty);
pm.environment.set('adminToken', data.accessToken);
```

拒绝越权步骤后置脚本：
```javascript
pm.test('普通用户不能管理', () => pm.response.to.have.status(403));
```

检查重点：撤权检查复用同一个 userToken，不能重新登录掩盖缓存问题。单独保存 adminToken 和 userToken，不能混用。OA/HR 的原始 JSON、请求编号和时间戳参与签名，签名后不再格式化 JSON。

Apifox 运行完成后导出报告，并记录项目地址、运行时间、环境和通过数。运行证据以实际报告为准。

官方参考：https://apifox.com/help/api-manage/import-api/intro/ ；https://docs.apifox.com/scripts
''',encoding='utf-8')
print(f'Built {len(fks)} FK relationships and {sum(len(x) for x in spec["paths"].values())} API operations')
