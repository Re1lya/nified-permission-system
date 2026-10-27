"""Generate physical ER sources from the checked-in MySQL DDL (no database writes).
Run python3 scripts/build-er-assets.py, then node scripts/render-er.cjs.
Only actual FOREIGN KEY constraints produce lines; logical references are documented separately.
"""
from pathlib import Path
import re, json, html, hashlib
R=Path(__file__).resolve().parents[1]; D=R/'docs/phase-one'
sql=(D/'database-mysql-phase-one.sql').read_text()
def cols(s):return tuple(x.strip().strip('`').lower() for x in s.split(','))
tables={}; bodies={}; keys={}; foreign=[]
for m in re.finditer(r'create table(?: if not exists)?\s+(\w+)\s*\((.*?)\)\s*(?:engine[^;]*)?;',sql,re.I|re.S):
 name,body=m.groups();bodies[name]=body;tables[name]={}; keys[name]=[]
 for line in body.splitlines():
  c=re.match(r'\s*(\w+)\s+([\w]+)(?:\([^)]*\))?(.*)',line)
  if c and c[1].lower() not in ('constraint','primary','foreign','unique','key','index'):
   tables[name][c[1].lower()]={'type':c[2].lower(),'nullable':'not null' not in c[3].lower()}
 for k in re.finditer(r'(primary key|unique(?: key\s+\w+)?)\s*\(([^)]+)\)',body,re.I):keys[name].append((k[1].lower().startswith('primary'),cols(k[2])))
 for f in re.finditer(r'foreign key\s*\(([^)]+)\)\s*references\s+(\w+)\s*\(([^)]+)\)',body,re.I):foreign.append((name,cols(f[1]),f[2],cols(f[3])))
for statement in sql.split(';'):
 m=re.search(r'alter table\s+(\w+).*?unique\s*\(([^)]+)\)',statement,re.I|re.S)
 if m:keys[m[1]].append((False,cols(m[2])))
 m=re.search(r'create unique index\s+\w+\s+on\s+(\w+)\s*\(([^)]+)\)',statement,re.I|re.S)
 if m:keys[m[1]].append((False,cols(m[2])))
 m=re.search(r'alter table\s+(\w+).*?foreign key\s*\(([^)]+)\)\s*references\s+(\w+)\s*\(([^)]+)\)',statement,re.I|re.S)
 if m:foreign.append((m[1],cols(m[2]),m[3],cols(m[4])))
for name,ks in keys.items():
 for pk,cs in ks:
  if pk:
   for c in cs:tables[name][c]['nullable']=False
edges=[]
for child,cs,parent,ps in foreign:
 assert child in tables and parent in tables
 assert all(c in tables[child] for c in cs) and all(p in tables[parent] for p in ps)
 edges.append({'child':child,'columns':list(cs),'parent':parent,'parentColumns':list(ps),'optionalParent':any(tables[child][c]['nullable'] for c in cs),'uniqueChild':any(set(k).issubset(cs) for _,k in keys[child]),'identifying':any(pk and set(cs).issubset(k) for pk,k in keys[child])})
mm=['erDiagram','    %% Physical foreign keys only. Parent existence at child side follows nullability.', '    %% Parent rows may have zero children; PK/UK on child FK limits children to one.']
for name,fields in tables.items():
 mm.append('    '+name+' {')
 for c,v in fields.items():
  marks=[]
  if any(pk and c in cs for pk,cs in keys[name]):marks.append('PK')
  if any(e['child']==name and c in e['columns'] for e in edges):marks.append('FK')
  if any(not pk and cs==(c,) for pk,cs in keys[name]):marks.append('UK')
  mm.append('        '+v['type']+' '+c+(' '+', '.join(marks) if marks else '')+' "'+('NULL' if v['nullable'] else 'NOT NULL')+'"')
 mm.append('    }')
for e in edges:
 mm.append('    '+e['parent']+' '+('|o' if e['optionalParent'] else '||')+('--' if e['identifying'] else '..')+('o|' if e['uniqueChild'] else 'o{')+' '+e['child']+' : "'+','.join(e['columns'])+'"')
(D/'database-er.mmd').write_text('\n'.join(mm)+'\n')
(D/'schema-catalog.json').write_text(json.dumps([{'name':n,'ddl':b.strip()} for n,b in bodies.items()],ensure_ascii=False,indent=2)+'\n')
groups={
 'rbac':('Identity and RBAC', ['sys_user','sys_account','sys_role','sys_permission','sys_user_role','sys_role_permission','biz_system','sys_login_session']),
 'company':('Organization and permission catalog',['sys_department','employee_profile','sys_post','sys_module','sys_function','permission_detail','permission_template','company_import_batch']),
 'platform':('Administration and lifecycle',['admin_department','managed_role','department_permission','account_policy','session_activity','login_attempt','approval_record','integration_event','audit_event','audit_archive','permission_review','hr_identity','sync_failure','platform_notification','data_dictionary']),
 'protocol':('Application access and protocol storage',['application_access','platform_signing_key','oauth2_registered_client','oauth2_authorization','oauth2_authorization_consent','SPRING_SESSION','SPRING_SESSION_ATTRIBUTES'])}
assert sorted(n for _,ns in groups.values() for n in ns)==sorted(tables)
colors=['#e0f2e9','#e4edfa','#fff1da','#efe6f8']
I=D/'images';I.mkdir(exist_ok=True)
def make_dot(names,title):
 selected=set(names);es=[e for e in edges if e['child'] in selected];extra={e['parent'] for e in es}-selected
 lines=['digraph ER {','graph [rankdir=LR, pack=true, packmode="array3", bgcolor="white", pad=0.4, nodesep=0.35, ranksep=0.8, fontname="Arial", fontsize=24, labelloc=t, label='+json.dumps(title+'\nPhysical FK constraints only; no line does not mean no business reference')+'];','node [shape=plain, fontname="Arial", fontsize=12];','edge [fontname="Arial", fontsize=10, color="#60788a", arrowsize=0.65];']
 for i,(g,(label,ns)) in enumerate(groups.items()):
  actual=set(ns)&selected
  if not actual:continue
  if len(names)!=len(tables):lines.append('subgraph cluster_'+g+' { label='+json.dumps(label)+'; color="#cbd5df"; style=rounded;')
  for n in ns:
   if n in actual:lines.append(node(n,colors[i]))
  if len(names)!=len(tables):lines.append('}')
 for n in sorted(extra):lines.append(node(n,'#f1f5f9'))
 for e in es:
  label=','.join(e['columns'])+' -> '+','.join(e['parentColumns'])
  # Direction is child to referenced parent. End labels state multiplicity at each end.
  lines.append(f'"{e["child"]}" -> "{e["parent"]}" [label={json.dumps(label)}, taillabel="'+('0..1' if e['uniqueChild'] else '0..N')+'", headlabel="'+('0..1' if e['optionalParent'] else '1')+'", labeldistance=1.8];')
 lines.append('}')
 return '\n'.join(lines)+'\n'
def node(n,color):
 fields=[]
 for c,v in tables[n].items():
  marks=[]
  if any(pk and c in cs for pk,cs in keys[n]):marks.append('PK')
  if any(e['child']==n and c in e['columns'] for e in edges):marks.append('FK')
  if any(not pk and cs==(c,) for pk,cs in keys[n]):marks.append('UK')
  if marks:fields.append(c+' ['+','.join(marks)+']'+(' nullable' if v['nullable'] else ''))
 rows=''.join('<TR><TD ALIGN="LEFT">'+html.escape(x)+'</TD></TR>' for x in fields)
 return '"'+n+'" [label=<<TABLE BORDER="1" CELLBORDER="0" CELLSPACING="0" CELLPADDING="6"><TR><TD BGCOLOR="'+color+'"><B>'+n+'</B></TD></TR>'+rows+'</TABLE>>];'
(I/'er-full.dot').write_text(make_dot(list(tables),f'Complete physical ER: {len(tables)} tables / {len(edges)} foreign keys'))
for g,(title,ns) in groups.items():(I/('er-'+g+'.dot')).write_text(make_dot(ns,title+f' ({len(ns)} tables; external parent tables repeated)'))
manifest={'ddlSha256':hashlib.sha256(sql.encode()).hexdigest(),'tableCount':len(tables),'foreignKeyCount':len(edges),'tables':list(tables),'foreignKeys':edges,'groups':{g:ns for g,(_,ns) in groups.items()}}
(D/'er-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('ER generated:',len(tables),'tables,',len(edges),'physical foreign keys')
