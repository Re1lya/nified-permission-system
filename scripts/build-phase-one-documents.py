"""Build four Word documents from reviewed prose and actual runtime metadata.
Requires bundled python-docx/Pillow. Run tests first to export schema and routes.
"""
from pathlib import Path
import json,re,shutil,xml.etree.ElementTree as ET
from docx import Document
from docx.shared import Cm,Pt,Inches,RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/phase-one';OUT.mkdir(exist_ok=True)
# Reuse the existing table/font helpers without executing the old two-document build.
helpers=(ROOT/'scripts/build-design-documents.py').read_text(encoding='utf-8').split("source=(OUT/")[0]
namespace={'__file__':str(ROOT/'scripts/build-design-documents.py')};exec(helpers,namespace)
p=namespace['p']
def table(d,headers,rows,widths=None):
 namespace['table'](d,headers,rows,widths)
 for cell in d.tables[-1].rows[0].cells:
  for para in cell.paragraphs:para.paragraph_format.keep_with_next=True

def doc(title,intro):
 d=namespace['doc'](title,intro);d.paragraphs[2].text='一期推进版本  |  2026 年 10 月 21 日'
 d.core_properties.subject='统一权限管理系统一期设计与实际实现边界'
 d.sections[0].page_width=Inches(8.5);d.sections[0].page_height=Inches(11)
 d.styles['Normal'].font.size=Pt(11)
 return d
def markdown(d,path):
 lines=path.read_text(encoding='utf-8').splitlines();i=0
 while i<len(lines):
  line=lines[i].strip()
  if line.startswith('# '):i+=1;continue
  if line.startswith('### '):d.add_heading(line[4:],2)
  elif line.startswith('## '):d.add_heading(line[3:],1)
  elif line.startswith('```'):
   block=[];i+=1
   while i<len(lines) and not lines[i].startswith('```'):block.append(lines[i]);i+=1
   para=d.add_paragraph('\n'.join(block));para.paragraph_format.line_spacing=1
   for r in para.runs:r.font.size=Pt(9)
  elif m:=re.fullmatch(r'!\[(.*)\]\((.+)\)',line):
   d.add_picture(str(path.parent/m[2]),width=Cm(16.5));d.paragraphs[-1].alignment=1;d.paragraphs[-1].paragraph_format.keep_with_next=True
   cap=d.add_paragraph(m[1]);cap.alignment=1
   for r in cap.runs:r.font.size=Pt(9)
  elif line.startswith('|'):
   rows=[]
   while i<len(lines) and lines[i].strip().startswith('|'):
    cells=[c.strip() for c in lines[i].strip().strip('|').split('|')]
    if not all(re.fullmatch(r'[-: ]+',c) for c in cells):rows.append(cells)
    i+=1
   width=[3,6,8] if len(rows[0])==3 else None
   table(d,rows[0],rows[1:],width);continue
  elif line:p(d,line)
  i+=1
def save(d,name):
 d.save(OUT/name);print(name)
def page(d,title):
 h=d.add_heading(title,1);h.paragraph_format.page_break_before=True

reports=[]
for path in (ROOT/'target/surefire-reports').glob('TEST-*.xml'):
 r=ET.parse(path).getroot();reports.append({k:int(r.get(k,0)) for k in ['tests','failures','errors','skipped']})
counts={k:sum(r[k] for r in reports) for k in ['tests','failures','errors','skipped']}
jacoco=ET.parse(ROOT/'target/site/jacoco/jacoco.xml').getroot();coverage={}
for c in jacoco.findall('counter'):
 missed,covered=int(c.get('missed')),int(c.get('covered'));coverage[c.get('type')]={'covered':covered,'missed':missed,'percent':round(covered/(covered+missed)*100,2) if covered+missed else 0}
evidence={'date':'2026-10-21','command':'mvn clean verify (COMPANY_WORKBOOK_DIR set)','tests':counts,'coverage':coverage,'database':'isolated H2'}
(OUT/'verification.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
shutil.copyfile(ROOT/'output/actual-import-result.txt',OUT/'actual-import.txt')
shutil.copyfile(ROOT/'output/phase-one-http-results.json',OUT/'http-verification.json')

requirements=doc('需求规格说明（Spec）','沿用原需求的 UC/BR 编号，给出用例模型、领域模型、业务流程、功能规格与当前状态，并建立页面、API、数据表之间的追踪关系。')
markdown(requirements,OUT/'requirements.md')
requirements.add_heading('13 本次实际验证摘要',1)
p(requirements,f"实际运行 {counts['tests']} 项测试，失败 {counts['failures']}，错误 {counts['errors']}，跳过 {counts['skipped']}。JaCoCo 行覆盖率 {coverage['LINE']['percent']}%，分支覆盖率 {coverage['BRANCH']['percent']}%。覆盖率来自本次实际运行报告。")
save(requirements,'01-需求分析文档.docx')

ui=doc('界面原型文档','本文件说明一期管理界面的页面组织和操作规则，并展示实际浏览器运行截图。界面基于 Vue 3、TypeScript、Tailwind 构建，沿用浅色中文后台风格。截图使用虚构演示数据，原始一万名员工未公开展示。')
ui.add_heading('1 布局与交互原则',1)
p(ui,'课堂明亮环境采用浅色背景、高对比正文和固定侧栏。顶部显示当前平台身份和退出入口；主体按任务展示列表、表单与反馈。管理操作成功后重新读取数据，避免界面显示旧状态。列表加载、空数据、校验失败、401 和 403 分别表达。')
p(ui,'一期界面入口为 /ui/index.html；侧栏按开始、基础管理、组织数据、运行记录、本人分组。前端隐藏不可用导航仅用于易用性，授权判断仍由后端执行。移动窄屏侧栏横向滚动，表格保留横向滚动。')
ui.add_heading('2 页面跳转与角色可见性',1)
p(ui,'登录成功进入主框架；首次登录或管理员重置密码后必须先在个人中心修改密码。侧栏按身份只渲染可见页面，后端对每个请求独立鉴权；无权限显示 403 并保留登录，令牌失效且无法刷新时回到登录页。')
ui.add_picture(str(OUT/'images/ui-navigation.png'),width=Cm(17));p(ui,'图：页面跳转与角色可见性（S 超级管理员、D 部门管理员、U 普通用户）')
ui.add_heading('3 页面清单（对照需求 20 页）',1)
p(ui,'下表逐项对应原需求第 10.1 节的 20 个页面，并列出为一期功能增加的页面。所有页面均在第 5–7 节附实际运行截图。')
table(ui,['序号','需求页面','实现页面（侧栏）','可见角色','截图'],[
 ('1','登录页','登录页','全部','5.1'),('2','主框架','主框架（顶栏 + 侧栏）','全部','5.2'),('3','工作台','工作台','S D U','5.2'),
 ('4','用户管理','用户管理','S D','5.3、6.1'),('5','角色管理','角色管理','S D','5.6'),('6','权限点管理','权限管理','S','5.7'),
 ('7','应用管理','应用管理','S','5.8'),('8','部门管理','部门管理','S','5.9'),('9','岗位管理','岗位管理','S','5.10'),
 ('10','数据字典','字典管理','S','5.13'),('11','会话管理','在线会话、部门会话','S D U / S D','5.14、5.15、6.2'),('12','操作日志','操作日志','S D','5.16、6.3'),
 ('13','登录日志','登录日志','S D','5.17'),('14','审批记录','审批记录、权限申请','S D / S D U','5.18、7.3'),('15','同步状态','同步记录','S','5.19'),
 ('16','个人中心','个人中心','S D U','5.20'),('17','403 页','访问受限','全部','7.4'),('18','404 页','页面不存在','全部','5.22'),
 ('19','门户首页','应用门户','S D U','5.21'),('20','我的权限','我的权限','S D U','7.1'),
 ('扩展','—','外部与临时人员（UC-C05）','S D','5.4'),('扩展','—','初始密码（BR-03）','S D','5.5'),('扩展','—','三表导入、功能权限目录','S','5.11、5.12'),('扩展','—','报销权限演示','S D U','7.2')],[1.2,2.6,4.6,2.4,2.6])
ui.add_heading('4 关键表单行为',1)
p(ui,'用户名和名称必填；编号字段要求正数；密码至少八位且包含大写、小写、数字、符号中至少三类。服务端进一步执行相同业务约束。回收角色、停用和删除等操作先说明结果并确认，提交过程中禁用按钮。密码不回填，不在成功提示中显示。')
p(ui,'导入先选择三份文件，得到完整数量和问题清单。存在错误时确认按钮禁用；无错误也必须勾选规则确认。未知权限保留未确认，不以默认全权限掩盖问题。成功显示统计，刷新导入批次和后续列表。')
views=[('5 超级管理员视角',[('5.1 登录页','ui-01-login.png','统一账号或手机号入口，表单使用可见标签。'),('5.2 主框架与工作台','ui-02-home.png','顶栏显示当前身份，侧栏按开始、基础管理、组织数据、运行记录、本人分组。'),
  ('5.3 用户管理','ui-03-users.png','分页列表与密码、角色、停用、下线、逻辑删除操作。'),('5.4 外部与临时人员','ui-04-external.png','临时人员 9 天后到期标为即将到期，可直接续期。'),
  ('5.5 初始密码','ui-05-initial-passwords.png','按部门为无密码员工生成随机初始密码，只显示一次并可下载分发清单。'),('5.6 角色管理','ui-06-roles.png','岗位同名角色与普通角色，分配和回收权限。'),
  ('5.7 权限管理','ui-07-permissions.png','显示类型与有效状态；功能下线时标记失效而不删除。'),('5.8 应用管理','ui-08-systems.png','业务系统目录。'),
  ('5.9 部门管理','ui-09-departments.png','部门树与负责人。'),('5.10 岗位管理','ui-10-posts.png','岗位对应同名角色，可应用权限模板。'),
  ('5.11 三表导入','ui-11-imports.png','选择三份表单、预览后整批提交。'),('5.12 功能权限目录','ui-12-catalog.png','按系统、模块、功能展示来源清单。'),
  ('5.13 字典管理','ui-13-dictionary.png','分类与字典项。'),('5.14 在线会话（本人）','ui-14-sessions.png','本人有效会话，可撤销。'),
  ('5.15 部门会话（全部员工）','ui-15-managed-sessions.png','超级管理员看到全部普通员工的有效会话。'),('5.16 操作日志','ui-16-audit.png','只读审计事件。'),
  ('5.17 登录日志','ui-17-login-logs.png','登录成功、失败与登出。'),('5.18 审批记录','ui-18-approval-records.png','OA 审批结论与对应申请。'),
  ('5.19 同步记录','ui-19-sync.png','HR 与 OA 签名事件。'),('5.20 个人中心','ui-20-profile.png','修改本人密码。'),
  ('5.21 应用门户','ui-21-portal.png','建立单点登录会话并打开 OA、HR 演示客户端。'),('5.22 页面不存在','ui-22-not-found.png','404 提示并引导返回导航。')]),
 ('6 部门管理员视角（信息中心）',[('6.1 用户管理','ui-23-dept-users.png','只列出绑定部门的员工，没有新增用户和编辑入口。'),('6.2 部门会话','ui-24-dept-sessions.png','只看到本部门员工的会话。'),
  ('6.3 操作日志','ui-25-dept-audit.png','包含超级管理员对本部门员工的操作（新建外部人员、HR 入职）。')]),
 ('7 普通用户视角',[('7.1 我的权限','ui-26-my-permissions.png','侧栏只有本人可见页面；有效权限为全部启用角色的并集。'),('7.2 报销权限演示','ui-27-expenses.png','持有 expense:list，受保护接口返回 200。'),
  ('7.3 权限申请','ui-28-applications.png','本人申请记录与 OA 审批状态。'),('7.4 访问受限','ui-29-forbidden.png','直接访问管理页面显示 403，登录状态保留。')])]
images=OUT/'images';images.mkdir(exist_ok=True)
for section,items in views:
 page(ui,section)
 for title,name,caption in items:
  path=ROOT/'output/playwright'/name
  if not path.exists():raise RuntimeError('Missing actual screenshot: '+name)
  shutil.copyfile(path,images/name)
  h=ui.add_heading(title,2);h.paragraph_format.keep_with_next=True
  p(ui,caption);ui.add_picture(str(path),width=Cm(16));ui.paragraphs[-1].paragraph_format.keep_with_next=True
  p(ui,'截图来源：本地实际运行（虚构演示数据），2026 年 10 月 22 日。')
save(ui,'02-界面原型文档.docx')

api=doc('API设计文档','本文件供前后端联调和课程验收使用，说明现有接口的鉴权方式、三表导入、OIDC 协议和 OA/HR 签名事件。正文描述已实现接口与 Apifox 检查方式，附录来自 Spring 实际注册路由，不包含任何真实密钥或令牌。')
markdown(api,OUT/'api-design.md')
endpoints=json.loads((ROOT/'output/phase-one-endpoints.json').read_text(encoding='utf-8'))
for e in endpoints:
 if e['path'].startswith('/api/integration'):
  for param in e['parameters']:
   if param['kind']=='body':param['fields']=[{'name':n,'type':ty} for n,ty in [('action','String'),('username','String'),('displayName','String'),('userId','Long'),('departmentId','Long'),('postId','Long'),('phone','String'),('company','String'),('roleIds','List'),('approvalId','Long'),('decision','String')]]
api.add_heading('11 实际注册的业务接口',1)
for prefix,title in [('/api/auth','认证'),('/api/users','旧账号接口'),('/api/roles','旧角色接口'),('/api/systems','业务系统'),('/api/permissions','权限目录'),('/api/company','三表和公司目录'),('/api/platform','范围管理和自助'),('/api/integration','外部事件'),('/api/oidc','OIDC 业务权限')]:
 api.add_heading(title,2)
 rows=[]
 for e in endpoints:
  if not e['path'].startswith(prefix):continue
  fields=[]
  for parameter in e['parameters']:
   if parameter['kind']=='body':fields.append('body: '+', '.join(f['name'] for f in parameter['fields']) if parameter['fields'] else 'body: '+parameter['type'])
   else:fields.append(parameter['name']+': '+parameter['type'])
  rows.append(('/'.join(e['methods']),e['path'],'\n'.join(fields) or '无请求体或使用路径参数'))
 table(api,['方法','路径','请求字段'],rows,[2,8.2,6.8])
p(api,'附录只列业务 Controller。标准 OIDC 协议端点由过滤器提供，不属于 Controller 路由映射。完整校验注解和返回 DTO 以代码为准。')
page(api,'12 Apifox 实际导入记录')
p(api,'CDWY 团队项目：https://app.apifox.com/project/8955177 。已导入 68 个接口、20 个数据模型、1 个本地环境和 3 个鉴权组件。')
api.add_picture(str(OUT/'images/apifox-import.png'),width=Cm(17))
save(api,'03-API设计文档.docx')

schema=json.loads((ROOT/'output/phase-one-schema.json').read_text(encoding='utf-8'))
purposes={'sys_user':'登录账号与显示姓名','sys_role':'RBAC0 角色','biz_system':'业务软件目录','sys_permission':'原子权限点','sys_user_role':'用户角色关联','sys_role_permission':'角色权限关联','sys_login_session':'可撤销的 JWT 会话','sys_department':'部门树与负责人','sys_post':'岗位与一对一角色','employee_profile':'员工组织和联系方式','sys_module':'应用下的功能模块','sys_function':'模块下的业务功能','permission_detail':'功能与操作权限关联','permission_template':'五类来源权限模板','company_import_batch':'已提交导入批次','admin_department':'管理员精确部门范围','account_policy':'账号过期 首次改密 权限版本和逻辑停用','login_attempt':'登录失败计数与锁定','audit_event':'只读审计事件','integration_event':'来源事件幂等记录','approval_record':'岗位角色申请与审批结论','data_dictionary':'分类字典','managed_role':'部门受管角色','department_permission':'部门授权上限','session_activity':'会话最近活动时间'}
meanings={'type':'权限类型 MENU/BUTTON/API','active':'权限点是否有效（失效保留授权但不生效）','target_department_id':'被操作员工所属部门，用于部门管理员查询','id':'记录主键','user_id':'账号编号','role_id':'角色编号','permission_id':'权限编号','department_id':'部门编号','post_id':'岗位编号','system_id':'业务系统编号','module_id':'模块编号','function_id':'功能编号','parent_id':'上级部门，可空','manager_id':'负责人账号，可空','manager_name':'来源表负责人姓名','username':'用户名，唯一','display_name':'显示姓名','password_hash':'BCrypt 摘要，可空','administrator':'平台超级管理员标记','enabled':'启用标记','name':'名称','code':'稳定编码','description':'说明','base_url':'业务入口地址','menu_path':'资源或菜单路径','phone':'手机号，不保证唯一','company':'分公司原始名称','action':'操作编码','fingerprint':'三文件 SHA256 指纹，唯一','actor_id':'操作账号编号','employees':'员工行数','departments':'部门行数','functions':'功能行数','created_at':'创建时间','issued_at':'签发时间','expires_at':'过期时间','person_type':'人员类别，当前默认 EMPLOYEE','must_change_password':'首次登录必须修改密码','version':'权限版本','deleted':'账号逻辑删除标记','failures':'失败次数','locked_until':'锁定截止时间','kind':'事件类型','target':'操作对象，不含请求密码','result':'处理结果','source':'外部来源 hr 或 oa','request_id':'业务事件或申请标识','digest':'请求体 SHA256 摘要','decision':'PENDING APPROVED REJECTED','category':'字典分类','session_id':'JWT 会话编号','last_seen':'最近访问时间'}
database=doc('数据库设计文档','本文件说明从原七表 RBAC0 扩展后的实际 25 张表、三表来源映射和主要约束。字段清单从本次 H2 运行数据库导出，与当前实体映射对应。配套提供 ER 图、关系源码和 MySQL 结构 SQL。')
database.add_heading('1 设计原则和整体关系',1)
p(database,'账号负责认证，员工档案负责部门、岗位、公司与电话；本版本为一对一。岗位拥有一个角色，但员工不会仅因岗位字段就自动获得该角色。实际授权由 sys_user_role 独立保存。')
table(database,['关系','基数','约束'],[('员工档案与账号','一对一','employee_profile.user_id 唯一外键'),('部门与员工','一对多','employee_profile.department_id 外键'),('部门与上级','自关联','parent_id 外键，循环在服务校验'),('岗位与角色','一对一','sys_post.role_id 唯一外键'),('用户与角色','多对多','关联组合唯一'),('角色与权限','多对多','关联组合唯一'),('系统 模块 功能 操作','逐级一对多','模块和功能的父项与名称组合唯一'),('部门管理员与部门','多对多','admin_department 组合唯一'),('外部来源与事件编号','组合唯一','integration_event 防重复执行')],[5,3,9])
p(database,'员工未设置密码时无法登录；密码摘要为空而不是使用统一默认密码。超级管理员由环境变量首次初始化，不从表单角色名称推断。表单原文保存在来源字段与目录中，实际员工授权单独维护。')
database.add_heading('2 实际数据规模和字段设计依据',1)
p(database,'真实三表验证为 111 部门、10000 员工、54 岗位、18 系统、115 模块、359 功能及 797 操作权限。新员工授权数为 0。五种权限模板不直接等于平台管理身份，也不自动替代岗位角色。')
p(database,'用户名和业务编码作为稳定业务键，内部 id 使用 bigint。显示名称不适合作为员工唯一标识。部门名称按当前表单规则全局唯一。手机号可能重复，因此当前不建立唯一约束。')
database.add_heading('3 数据字典',1)
for name,columns in schema.items():
 database.add_heading(purposes.get(name,name)+'  '+name,2)
 rows=[]
 for column in columns:
  field=column['COLUMN_NAME'].lower();typ=column['DATA_TYPE'].lower();length=column['CHARACTER_MAXIMUM_LENGTH']
  if length:typ=f'varchar({length})'
  if typ=='timestamp with time zone':typ='timestamp(6) UTC'
  rows.append((field,typ,'可空' if column['IS_NULLABLE']=='YES' else '非空',meanings.get(field,'业务字段')))
 table(database,['字段','类型','空值','含义'],rows,[4.6,4.2,1.7,6.5])
 if name in ('account_policy','session_activity','managed_role'):p(database,'以关联主体编号为主键，不使用独立自增编号。各关系的外键以配套 SQL 和 ER 图实线为准。')
database.add_heading('4 主要约束和一致性',1)
p(database,'sys_user.username、角色和系统及权限的 code 唯一。employee_profile.user_id、sys_post.role_id、permission_detail.permission_id 唯一。用户角色、角色权限、管理员部门、部门委派权限、模块名称和功能名称分别建立组合唯一约束。集成事件以 source 和 request_id 唯一，导入批次以 fingerprint 唯一。')
p(database,'数据库外键约束实体引用存在；组织树无环、管理范围、模板使用方式、审批只处理一次等规则在服务层处理。HR 调岗、审批、三表提交均有数据库事务。数据库唯一键是并发下最终防线，当前进程使用 synchronized 顺序处理集成事件。')
p(database,'应用和角色停用保留关联，但不再参与有效权限计算。新用户管理删除接口通过 account_policy.deleted 和 sys_user.enabled 逻辑停用，并撤销会话、回收角色。旧接口和部分目录仍使用物理删除，已有引用时拒绝或由外键阻止。')
database.add_heading('5 索引与结构 SQL',1)
p(database,'用户名、角色权限关联与外部事件编号采用唯一约束。会话按用户及到期时间建索引，审计按操作人及时间建索引。database-mysql-phase-one.sql 提供 CREATE TABLE、主键、唯一键、外键和索引定义，使用 InnoDB、utf8mb4 与 UTC 时间；脚本仅含结构。')
database.add_heading('6 本地数据准备',1)
p(database,'H2 演示库使用 create-drop，停止进程后数据清空。重启后可重新导入三表或使用虚构演示表单。OIDC 客户端、授权、同意与 RSA 密钥保存在内存，与数据库会话区分。')
mysql=json.loads((OUT/'mysql-verification.json').read_text(encoding='utf-8'))
database.add_heading('7 MySQL 8 实测',1)
p(database,f"{mysql['server']} 上执行 database-mysql-phase-one.sql：{mysql['schema']['result']}，{mysql['schema']['tables']} 张表、{mysql['schema']['foreignKeys']} 个外键、{mysql['schema']['indexes']} 个索引；{mysql['schema']['hibernate']}。")
imp=mysql['realImport'];p(database,f"在该库上通过 HTTP 导入三份原始表：{imp['counts']['departments']} 部门、{imp['counts']['employees']} 员工、{imp['createdPermissions']} 操作权限，预览 {imp['previewSeconds']} 秒、提交 {imp['commitSeconds']} 秒，重复提交识别为已导入。串联场景 {mysql['scenarios']['requests']['total']} 个请求 / {mysql['scenarios']['assertions']['total']} 条断言全部通过，HTTP 验收 {mysql['httpChecks']['passed']}/{mysql['httpChecks']['total']} 通过。")
p(database,'实测发现的问题：'+mysql['defectFound']+' 未覆盖：'+mysql['notCovered'])
page(database,'ER 图：完整结构')
p(database,'每张表只出现一次，框内列出主键与外键列，连线由外键表指向被引用表。下面三张分组图是同一结构按业务域的放大。')
database.add_picture(str(OUT/'images/er-full.png'),width=Cm(17))
for title,file in [('RBAC0 授权','er-rbac.png'),('组织与功能目录','er-company.png'),('管理与集成记录','er-platform.png')]:
 page(database,'ER 图：'+title)
 p(database,'方框为实际表，连线标明外键；1 为被引用端，N 为引用端，0..1 为唯一且可选的关联。完整可编辑关系见 database-er.mmd。')
 database.add_picture(str(OUT/'images'/file),width=Cm(17))
save(database,'04-数据库设计文档.docx')

# Produce schema-only MySQL candidate; exclude H2 accounts, fixture counts and data.
raw=(ROOT/'output/phase-one-h2.sql').read_text(encoding='utf-8')
def sql_convert(text):
 text=text.replace('"PUBLIC".','');text=re.sub(r'"([A-Z0-9_]+)"',lambda m:'`'+m[1].lower()+'`',text)
 text=re.sub(r'GENERATED BY DEFAULT AS IDENTITY\([^)]*\)','AUTO_INCREMENT',text)
 return text.replace('CHARACTER VARYING','VARCHAR').replace(' WITH TIME ZONE','').replace(' NULLS DISTINCT','').replace(' NOCHECK','')
creates=[]
for match in re.finditer(r'CREATE MEMORY TABLE "PUBLIC"\."(\w+)"\((.*?)\);',raw,re.S):
 name,body=match.groups();pk=re.search(r'ALTER TABLE "PUBLIC"\."'+name+r'" ADD CONSTRAINT .*? PRIMARY KEY\((.*?)\);',raw)
 creates.append('CREATE TABLE `'+name.lower()+'` ('+sql_convert(body)+',\n    PRIMARY KEY('+sql_convert(pk[1])+')\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;')
alters=[sql_convert(m[0]) for m in re.finditer(r'ALTER TABLE .*?;',raw,re.S) if 'PRIMARY KEY' not in m[0]]
indexes=[sql_convert(m[0]) for m in re.finditer(r'CREATE (?:UNIQUE )?INDEX .*?;',raw,re.S)]
(OUT/'database-mysql-phase-one.sql').write_text('-- Schema SQL for a new isolated database. No data or credentials.\nSET time_zone = \'+00:00\';\n\n'+'\n\n'.join(creates+alters+indexes)+'\n',encoding='utf-8')
# Reviewable route inventory and Postman skeleton, no live credentials.
(OUT/'api-routes.json').write_text(json.dumps(endpoints,ensure_ascii=False,indent=2),encoding='utf-8')
items=[]
for e in endpoints:
 if not e['path'].startswith('/api/'):continue
 path=re.sub(r'\{(\w+)\}',r'{{\1}}',e['path'])
 for method in e['methods']:
  req={'method':method,'url':'{{baseUrl}}'+path,'header':[{'key':'Authorization','value':'Bearer {{accessToken}}'}],'description':'鉴权和请求字段请参见 API设计文档；集成端点需要脚本生成签名。'}
  body=next((p for p in e['parameters'] if p['kind']=='body'),None)
  if body and body['fields']:
   req['body']={'mode':'raw','raw':json.dumps({f['name']:None for f in body['fields']},ensure_ascii=False,indent=2),'options':{'raw':{'language':'json'}}}
  items.append({'name':method+' '+e['path'],'request':req})
collection={'info':{'name':'统一权限管理 一期接口目录','schema':'https://schema.getpostman.com/json/collection/v2.1.0/collection.json','description':'路由目录，不是无参数一键验收；先按 API 文档填写具体数据，使用 verify-phase-one.py 验证完整签名流程。'},'variable':[{'key':'baseUrl','value':'http://127.0.0.1:18081'},{'key':'accessToken','value':''}],'item':items}
(OUT/'phase-one.postman_collection.json').write_text(json.dumps(collection,ensure_ascii=False,indent=2),encoding='utf-8')
