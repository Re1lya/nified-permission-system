"""Read-only consistency checks for the current submission artifacts; no application tests."""
from pathlib import Path
import hashlib, json, re, xml.etree.ElementTree as ET
R = Path(__file__).resolve().parents[1]
D = R / 'docs/phase-one'
def read(p): return json.loads(p.read_text())
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def require(condition, message):
    if not condition: raise SystemExit('FAIL: ' + message)
manifest = read(D/'er-manifest.json')
sql = (D/'database-mysql-phase-one.sql').read_text()
names = set(re.findall(r'create table(?: if not exists)?\s+(\w+)',sql,re.I))
require(len(names)==38 and names==set(manifest['tables']), 'DDL and manifest table sets')
require(len(re.findall(r'foreign key\s*\(',sql,re.I))==manifest['foreignKeyCount']==26, 'physical FK count')
require(sha(D/'database-mysql-phase-one.sql')==manifest['ddlSha256'], 'DDL digest')
mapped=set()
for p in (R/'src/main/java').rglob('*.java'):
    mapped.update(re.findall(r'@(?:[\w.]*\.)?(?:Table|SecondaryTable)\s*\(\s*name\s*=\s*"([^"]+)"',p.read_text()))
protocol=set()
for file in ('oidc-schema.sql','session-schema-mysql.sql'):
    protocol.update(re.findall(r'create table(?: if not exists)?\s+(\w+)',(R/'src/main/resources'/file).read_text(),re.I))
require(len(mapped)==33 and mapped|protocol==names,'Java mappings plus protocol SQL table sets')
mermaid=(D/'database-er.mmd').read_text()
require(set(re.findall(r'^    (\w+) \{$',mermaid,re.M))==names,'Mermaid entity set')
require(len(re.findall(r'^    \w+ [|o].* : ',mermaid,re.M))==26,'Mermaid relation count')
for relation in ('sys_user ||--o| sys_account','sys_user ||..o| employee_profile','sys_permission ||..o| permission_detail','sys_role ||..o| sys_post','sys_department |o..o{ sys_department','sys_user |o..o{ sys_department'):
    require(relation in mermaid,'cardinality: '+relation)
root=ET.parse(D/'images/er-full.svg').getroot(); ns={'s':'http://www.w3.org/2000/svg'}
svg_names={g.find('s:title',ns).text for g in root.findall('.//s:g',ns) if g.attrib.get('class')=='node'}
require(svg_names==names,'SVG table set')
require(sum(g.attrib.get('class')=='edge' for g in root.findall('.//s:g',ns))==26,'SVG relation count')
api=read(D/'apifox/openapi.json');methods={'get','post','put','delete','patch','head','options','trace'}
ops={(p,m.upper()) for p,v in api['paths'].items() for m in v if m in methods}
routes={(v['path'],v['method']) for v in read(D/'api-routes.json')}
require(ops==routes and len(ops)==98,'98 offline routes match contract')
preview=api['paths']['/api/company/imports/preview']['post']
files={'departments','employees','permissions'}
require(not any(p['name'] in files for p in preview.get('parameters',[])),'multipart fields are not query parameters')
body=preview['requestBody']['content']['multipart/form-data']['schema']
require(set(body['properties'])==files and set(body['required'])==files,'three required upload files')
require(all(p.get('format')=='binary' for p in body['properties'].values()),'upload field types')
def refs(value):
    if isinstance(value,dict):
        if '$ref' in value:
            ref=value['$ref'];require(ref.startswith('#/'),'local OpenAPI reference')
            target=api
            for part in ref[2:].split('/'):
                part=part.replace('~1','/').replace('~0','~');require(part in target,'OpenAPI reference '+ref);target=target[part]
        for v in value.values():refs(v)
    elif isinstance(value,list):
        for v in value:refs(v)
refs(api)
sync=read(D/'apifox/project.json')
require(sync['sync']['openapiSha256']==sha(D/'apifox/openapi.json') and sync['version']==api['info']['version'],'Apifox sync digest and version')
summary=read(R/'docs/submission/evidence/test-summary.json')
for path,digest in summary['sourceSha256'].items():require(sha(R/path)==digest,'tested source unchanged: '+path)
checked_links=0
for p in [R/'README.md',D/'README.md',*(R/'docs/submission').glob('*.adoc')]:
    text=p.read_text()
    links=re.findall(r'(?:link:|image::)([^\s\[]+)\[',text) if p.suffix=='.adoc' else re.findall(r'\]\(([^)]+)\)',text)
    for url in links:
        if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:',url) or url.startswith('#'):continue
        url=url.split('#')[0]
        require((p.parent/url).exists(),str(p.relative_to(R))+' link '+url);checked_links+=1
print(json.dumps({'status':'PASS','tables':len(names),'mappedTables':len(mapped),'protocolTables':len(protocol),'physicalForeignKeys':26,'apiOperations':len(ops),'apiSchemas':len(api['components']['schemas']),'apifoxVersion':sync['version'],'unchangedTestedSourceFiles':len(summary['sourceSha256']),'existingRelativeLinks':checked_links},ensure_ascii=False,indent=2))
