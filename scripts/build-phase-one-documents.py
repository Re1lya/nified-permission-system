"""Render the four current design documents from source contracts, without consuming test reports.
Run with the document skill's bundled Python. Render/inspect the resulting Word files separately.
"""
from pathlib import Path
import re,json
from docx import Document
from docx.shared import Cm,Pt,RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from PIL import Image,ImageDraw,ImageFont
R=Path(__file__).resolve().parents[1];D=R/'docs/phase-one';I=D/'images'
FONT='/System/Library/Fonts/Supplemental/Arial Unicode.ttf'

def text(s):
 s=re.sub(r'\[([^\]]+)\]\([^)]+\)',r'\1',str(s));return s.replace('**','').replace('`','')
def title(s):return re.sub(r'[^\w\s\u3400-\u9fff]',' ',s).replace('_',' ')
def doc(name,intro):
 d=Document();sec=d.sections[0];sec.page_width=Cm(21);sec.page_height=Cm(29.7);sec.top_margin=sec.bottom_margin=Cm(1.8);sec.left_margin=sec.right_margin=Cm(2)
 for sty in d.styles:
  for border in list(sty.element.iter(qn('w:pBdr'))):border.getparent().remove(border)
 for name_,size in [('Normal',10.5),('Title',23),('Subtitle',11),('Heading 1',15),('Heading 2',12),('Heading 3',11)]:
  st=d.styles[name_];st.font.name='Arial Unicode MS';st.font.size=Pt(size);st.font.color.rgb=RGBColor(0,0,0);st.element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'),'Arial Unicode MS');st.paragraph_format.space_after=Pt(6)
 d.styles['Normal'].paragraph_format.line_spacing=1.15
 d.add_paragraph('统一权限管理系统',style='Subtitle');d.add_paragraph(name,style='Title');d.add_paragraph('一期需求对齐修订  2026 年 10 月 22 日')
 d.add_paragraph(intro);d.add_paragraph('依据 DOC 01 V1.0 原始需求  本次未运行项目测试',style='Subtitle')
 foot=sec.footer.paragraphs[0];foot.alignment=2;field=OxmlElement('w:fldSimple');field.set(qn('w:instr'),'PAGE');foot._p.append(field)
 d.core_properties.title=name;d.core_properties.subject='一期需求与实现设计';d.core_properties.author='CDWY'
 return d

def table(d,headers,rows,widths=None):
 widths=widths or [17/len(headers)]*len(headers);t=d.add_table(rows=1,cols=len(headers));t.autofit=False
 for col,w in zip(t.columns,widths):col.width=Cm(w)
 for idx,values in enumerate([headers]+rows):
  row=t.rows[0] if idx==0 else t.add_row();pr=row._tr.get_or_add_trPr();pr.append(OxmlElement('w:cantSplit'))
  if idx==0:pr.append(OxmlElement('w:tblHeader'))
  for c,w,v in zip(row.cells,widths,values):
   c.width=Cm(w);c.vertical_alignment=1;c.text=text(v)
   tc=c._tc.get_or_add_tcPr();b=OxmlElement('w:tcBorders')
   for side in ['top','left','bottom','right']:
    el=OxmlElement('w:'+side);el.set(qn('w:val'),'single');el.set(qn('w:sz'),'4');el.set(qn('w:color'),'D9D9D9');b.append(el)
   tc.append(b);shade=OxmlElement('w:shd');shade.set(qn('w:fill'),'DBE7F1' if idx==0 else 'FFFFFF' if idx%2 else 'F7F9FB');tc.append(shade)
   margins=OxmlElement('w:tcMar')
   for side in ['top','bottom','left','right']:
    el=OxmlElement('w:'+side);el.set(qn('w:w'),'95');el.set(qn('w:type'),'dxa');margins.append(el)
   tc.append(margins)
   for p in c.paragraphs:
    p.paragraph_format.space_after=Pt(2);p.paragraph_format.line_spacing=1.1
    for r in p.runs:
     r.font.size=Pt(9);r.bold=idx==0;r.text=re.sub(r'([a-z])([A-Z])',lambda m:m[1]+'\u200b'+m[2],r.text).replace('/','/\u200b').replace('_','_\u200b')
 d.add_paragraph().paragraph_format.space_after=Pt(1)

def markdown(d,path):
 lines=path.read_text().splitlines();i=0
 while i<len(lines):
  l=lines[i].strip()
  if l.startswith('# '):i+=1;continue
  if l.startswith('## '):d.add_heading(title(l[3:]),1)
  elif l.startswith('### '):d.add_heading(title(l[4:]),2)
  elif l.startswith('|'):
   rows=[]
   while i<len(lines) and lines[i].strip().startswith('|'):
    cells=[x.strip() for x in lines[i].strip().strip('|').split('|')]
    if not all(re.fullmatch('[-: ]+',x) for x in cells):rows.append(cells)
    i+=1
   if rows:
    widths=[3.7,8.1,5.2] if path.name=='requirements.md' else [2,9,6]
    table(d,rows[0],rows[1:],widths if len(rows[0])==3 else None)
   continue
  elif l.startswith('```'):
   block=[];i+=1
   while i<len(lines) and not lines[i].startswith('```'):block.append(lines[i]);i+=1
   content='\n'.join(block)
   if l=='```json':
    try:content=json.dumps(json.loads(content),ensure_ascii=False,indent=2)
    except ValueError:pass
   for line in content.splitlines():
    p=d.add_paragraph(line);p.paragraph_format.space_after=Pt(0);p.paragraph_format.line_spacing=1
    for run in p.runs:run.font.size=Pt(9)
  elif l:d.add_paragraph(text(l))
  i+=1

def wireframe(name,heading,filters,rows):
 im=Image.new('RGB',(1200,650),'#F6F8FA');dr=ImageDraw.Draw(im);f=ImageFont.truetype(FONT,23);small=ImageFont.truetype(FONT,20);big=ImageFont.truetype(FONT,32)
 dr.rectangle((0,0,1200,68),fill='white');dr.text((25,17),'统一权限管理系统   设计示意',font=big,fill='#172133')
 dr.rectangle((0,68,215,650),fill='#E8EDF3')
 for n,label in enumerate(['应用门户','用户与角色','应用接入','权限复核','审计归档','同步异常','部门通知']):dr.text((20,100+n*58),label,font=f,fill='#26344A')
 dr.text((250,95),heading,font=big,fill='#172133');dr.text((250,150),filters,font=small,fill='#364459')
 for n,row in enumerate(rows):
  y=205+n*70;dr.rectangle((250,y,1160,y+57),fill='white',outline='#CED6DF');dr.text((268,y+15),row,font=small,fill='#172133')
 dr.text((250,610),'代码对应的交互设计示意  不是运行截图或测试证据',font=small,fill='#596778');path=I/name;im.save(path);return path

def image(d,path,caption):
 d.add_picture(str(path),width=Cm(17));d.paragraphs[-1].paragraph_format.keep_with_next=True;d.add_paragraph(caption)

req=doc('一期需求分析与实现说明','以原始需求为准说明一期范围、业务规则、实现方法与待验收内容；不把历史验证记录或设计取舍当作本次功能验收。')
markdown(req,D/'requirements.md');req.save(D/'01-需求分析文档.docx')

ui=doc('一期界面原型设计','说明 Vue 管理门户的页面、可见性、关键表单及失败反馈。新增流程附代码对应的示意图，未使用旧运行截图证明新功能。')
ui.add_heading('1 导航与权限',1);ui.add_paragraph('登录后加载本人身份、具名管理能力与业务权限。首次改密时进入个人中心；管理页面根据服务端能力显示，报销等业务按钮根据权限编码显示。每次切换页面重新读取权限，后台仍独立检查身份与管辖范围。')
ui.add_paragraph('原需求的二十个页面保留：登录、主框架、工作台、用户、角色、权限点、应用、部门、岗位、字典、会话、操作日志、登录日志、审批记录、同步状态、个人中心、403、404、门户和我的权限。修订新增应用接入验收、管理员成员、复核及其记录、归档查询、同步异常和部门通知。')
table(ui,['身份','可见内容','限制'],[['超级管理员','全局管理、应用接入、管理员成员','不能解除首次内置管理员身份，不能修改本人超级管理员成员状态'],['部门管理员','本部门员工、授权、复核、审计及组织只读','不能维护其他部门或管理员；不自动包含子部门'],['普通用户','门户、本人权限、会话、密码、OA 申请状态','不能进入管理页面，业务按钮须有对应权限']], [3,7,7])
ui.add_heading('2 应用接入与门户',1);ui.add_paragraph('应用目录注册后进入接入配置，填写业务系统编号、客户端编号、入口和回调。密钥输入框不回显。配置修改后恢复待验收，验收人逐项确认并填写证据。门户只显示已启用且本人获授权的应用；暂不能改造的系统明确标注原系统登录。')
image(ui,wireframe('design-applications-current.png','应用接入与验收','选择业务系统    配置入口与回调    保存后重新验收',['业务系统  信息平台      客户端  office','入口地址  https://office.example.invalid/','回调地址  https://office.example.invalid/callback','登录  回调  鉴权  撤权  隔离     逐项确认','验收证据  记录实际观察结果后启用']),'应用接入页面设计示意')
ui.add_heading('3 员工与管理员',1);ui.add_paragraph('新员工生成拼音账号及随机初始密码，不自动授权。初始密码按受管部门一次性发放；下载清单中的单元格正确转义，不将姓名当作表格公式。用户编辑、停用、回收和下线操作执行服务端范围校验。管理员成员页面区分超级管理员与部门绑定，变更前提示目标会话将失效。')
ui.add_heading('4 权限复核',1);ui.add_paragraph('候选列表按员工与角色展示并分页。选择一项后必须填写复核理由，确认保留或回收。后台再次确认关系仍存在，避免对过期列表重复操作。历史记录展示执行人、时间、对象和结论。')
image(ui,wireframe('design-review-current.png','权限复核','受管员工角色    分页查看    复核理由必填',['员工  zhangsan       岗位角色  业务经办','当前授权来源  岗位角色关系','复核理由  岗位职责已确认','操作  确认保留        回收角色并记录','记录  操作人  目标员工  角色  结论  时间']),'复核流程设计示意')
ui.add_heading('5 审计归档与同步异常',1);ui.add_paragraph('审计页按操作人、事件类型、时间区间查询，并可切换归档。部门范围由后台约束，不提供跨部门选择绕过入口。同步异常页展示请求编号、错误摘要与处理状态；处理理由必填，修复后由源系统重新签名发送。原始 HR 数据和密钥不写入错误日志。')
image(ui,wireframe('design-audit-current.png','审计与归档','操作人  事件类型  开始时间  结束时间  归档开关',['2026 10 22  权限复核  KEEP  员工 12','2026 10 22  调岗交接  SUCCESS  员工 18','在线与归档使用相同的部门查询边界','记录只读  不提供修改和删除入口','分页  上一页     第 1 页     下一页']),'审计查询设计示意')
ui.add_heading('6 OA 与异常反馈',1);ui.add_paragraph('权限申请页展示 OA 登记的申请和当前结论，申请按钮打开已验收的 OA 入口，不在平台自建审批流。未配置入口时提示联系管理员。登录失效可尝试刷新；刷新失败返回登录；403 保留会话并提示权限不足；409 提醒刷新数据。保存时禁用重复提交按钮。')
ui.add_heading('7 界面验收边界',1);ui.add_paragraph('本次前端通过 TypeScript 类型检查与生产构建。没有逐页运行点击或连接企业 OA/HR 验收。示意图用于设计沟通，旧 screenshots 目录中的图片只反映旧提交。');ui.save(D/'02-界面原型文档.docx')

api=doc('一期 API 设计','说明统一响应、认证、签名回调、应用接入和新增管理接口。接口清单来自当前编译类的离线反射，不代表已经发送请求验收。');markdown(api,D/'api-design.md');api.save(D/'03-API设计文档.docx')

db=doc('一期数据库设计','当前结构包含业务、OAuth2 和 HTTP 会话共三十八张表。结构根据 Hibernate 元数据及 Spring 官方 JDBC schema 离线生成；本次未在 MySQL 执行脚本。')
db.add_heading('1 数据域与关键关系',1)
table(db,['数据域','主要表','设计目的'],[['身份与组织','sys_user sys_account employee_profile hr_identity sys_department sys_post','分离人员、凭证和稳定 HR 外部编号'],['授权','sys_role sys_permission sys_user_role sys_role_permission','RBAC0 权限并集及岗位同名角色'],['应用接入','application_access oauth2_registered_client oauth2_authorization oauth2_authorization_consent','业务目录关联协议配置与持久化授权'],['会话与密钥','sys_login_session session_activity platform_signing_key SPRING_SESSION 系列表','平台令牌、浏览器会话、共享密钥'],['审计与同步','audit_event audit_archive permission_review integration_event sync_failure platform_notification','复核、长期保留、失败记录和交接通知']], [2.7,7.5,6.8])
db.add_paragraph('sys_account.user_id 同时为主键和外键，一期按一人一账号持久化。hr_identity 以 kind 加 external_id 唯一定位本地记录；此多态映射由服务校验，不伪造数据库外键。应用配置的 system_id 由服务锁定和检查。审计归档沿用原事件编号，保留对象、操作人、部门及时间。完整 ER 源码见 database-er.mmd。')
db.add_heading('2 事务与一致性',1);db.add_paragraph('员工调岗、授权、权限版本更新在同一事务中提交。OA 审批对申请记录加锁，重复结论不重复授予角色。HR 批次整批原子提交；失败摘要在回滚后单独保存。归档任务锁定一批到期日志，复制到归档表后删除在线副本，同一事务内完成。')
db.add_heading('3 凭证和历史数据',1);db.add_paragraph('密码只保存 BCrypt。OIDC 私钥位于 platform_signing_key，不通过接口返回；该表访问权限及数据库备份应按凭证管理。平台 JWT 密钥由环境变量注入，多实例必须相同。HTTP 会话和 OAuth2 授权共享数据库，进程重启不再主动更换签名密钥。')
db.add_heading('4 新库与旧库迁移',1);db.add_paragraph('新库执行 database-mysql-phase-one.sql。旧提交 01b94f06 的二十五表安装执行 ../migrations/20261022-phase-one-completion.sql 一次。迁移前停止写入并备份；MySQL DDL 隐式提交，失败后不能盲目重跑。先复制账号凭证至 sys_account，再移除旧列；已有登录名保持不变，首次管理员标为内置管理员。旧内存中的 OIDC 授权无法迁移，需重新登录。')
db.add_paragraph('迁移脚本没有在本次运行，因此表数和字段一致性属于离线元数据检查结果。数据库版本兼容、迁移前后数据核对、回滚恢复及多实例行为仍需运行验收。')
catalog=json.loads((D/'schema-catalog.json').read_text())
db.add_heading('5 表结构目录',1)
for entry in catalog:
 db.add_heading(entry['name'].replace('_',' '),2)
 fieldrows=[]
 for line in entry['ddl'].splitlines():
  m=re.match(r'\s*([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z]+(?:\([^)]*\))?)(.*)',line)
  if m and m[1].lower() not in ['primary','constraint','foreign','unique']:
   fieldrows.append([m[1],m[2], '非空' if 'not null' in m[3].lower() else '可空'])
 table(db,['字段','类型','空值约束'],fieldrows,[7,6,4])
db.add_heading('6 索引与外键',1);db.add_paragraph('唯一性包括账号名、外部来源编号、请求幂等编号、客户端编号，以及原有用户角色和角色权限关系。外键和索引的完整名称以配套 SQL 为准。不能用逻辑删除绕过员工、子部门或角色引用校验。归档查询和同步历史的容量、查询计划和索引效果须结合真实数据评估。')
db.save(D/'04-数据库设计文档.docx')
for p in sorted(D.glob('0[1-4]-*.docx')):print(p.name,p.stat().st_size)
