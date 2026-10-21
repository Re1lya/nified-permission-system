"""Local HTTP acceptance including real authorization-code + PKCE exchange.
Secrets are read from environment; evidence contains no tokens or employee data.
"""
import base64, hashlib, hmac, http.cookiejar, json, os, re, secrets, time
from pathlib import Path
from urllib.request import Request, build_opener, HTTPCookieProcessor, HTTPRedirectHandler
from urllib.error import HTTPError
from urllib.parse import urlencode, urlparse, parse_qs
from html import unescape
BASE=os.environ.get('PERMISSION_BASE','http://127.0.0.1:18081')
if urlparse(BASE).hostname not in ('127.0.0.1','localhost'):raise SystemExit('Local verification only')
class CallbackStop(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlparse(newurl).port in (18101,18102):return None
        return super().redirect_request(req,fp,code,msg,headers,newurl)
browser=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()),CallbackStop())
results=[]
def http(path,method='GET',body=None,token=None,form=False,headers=None,opener=None):
    data=None if body is None else (urlencode(body) if form else json.dumps(body)).encode()
    headers=dict(headers or {});headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
    if token:headers['Authorization']='Bearer '+token
    request=Request(path if path.startswith('http') else BASE+path,data=data,method=method,headers=headers)
    try:
        with (opener or build_opener()).open(request,timeout=30) as r:return r.status,r.read().decode(),dict(r.headers),r.url
    except HTTPError as e:return e.code,e.read().decode(),dict(e.headers),e.url
def api(path,method='GET',body=None,token=None,expect=200):
    status,text,_,_=http(path,method,body,token)
    assert status==expect,(path,status,text[:200]);return json.loads(text) if text else None
def record(name):results.append({'check':name,'result':'PASS'})
def csrf(text):return unescape(re.search(r'name=[\"\']_csrf[\"\'] value=[\"\']([^\"\']+)',text)[1])
def oidc(client,admin_token):
    verifier=secrets.token_urlsafe(48);state=secrets.token_urlsafe(24);nonce=secrets.token_urlsafe(24)
    redirect='http://127.0.0.1:'+('18101' if client=='oa-demo' else '18102')+'/callback'
    challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
    params={'response_type':'code','client_id':client,'redirect_uri':redirect,'scope':'openid profile','state':state,'nonce':nonce,'code_challenge':challenge,'code_challenge_method':'S256'}
    status,text,headers,url=http('/oauth2/authorize?'+urlencode(params),headers={'Accept':'text/html'},opener=browser)
    assert '/login' not in url, 'SSO bridge failed'
    if status==200:
        assert '/consent' in url,(status,url,text[:100])
        fields=parse_qs(urlparse(url).query)
        status,text,headers,url=http('/oauth2/authorize','POST',{'client_id':client,'state':fields['state'][0],'scope':'profile','_csrf':csrf(text)},form=True,opener=browser)
    assert status==302,(status,text[:200]);callback=parse_qs(urlparse(headers['Location']).query);assert callback['state'][0]==state
    code=callback['code'][0]
    payload={'grant_type':'authorization_code','client_id':client,'redirect_uri':redirect,'code':code,'code_verifier':verifier}
    status,text,_,_=http('/oauth2/token','POST',payload,form=True)
    assert status==200,(status,text[:200]);tokens=json.loads(text)
    import importlib.util
    spec=importlib.util.spec_from_file_location('oidc_demo',Path(__file__).with_name('oidc-demo.py'));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    module.ISSUER=BASE;metadata=api('/.well-known/openid-configuration');claims=module.verify(tokens['id_token'],client,nonce,metadata)
    assert claims['sub']
    api('/api/oidc/permissions/check?'+urlencode({'code':'ungranted:demo:view','clientId':client}),token=tokens['access_token'],expect=403)
    replay=http('/oauth2/token','POST',payload,form=True)[0];assert replay==400
    return tokens['access_token']
def signed(source,event,request_id):
    body=json.dumps(event);timestamp=str(int(time.time()));secret=os.environ['PERMISSION_'+source.upper()+'_SECRET']
    signature=hmac.new(secret.encode(),(source+'\n'+timestamp+'\n'+request_id+'\n'+body).encode(),hashlib.sha256).hexdigest()
    status,text,_,_=http('/api/integration/'+source,'POST',event,headers={'X-Request-Id':request_id,'X-Timestamp':timestamp,'X-Signature':signature})
    assert status==200,(status,text);return json.loads(text)

if __name__=='__main__':
    admin=api('/api/auth/login','POST',{'username':os.environ['PERMISSION_ADMIN_USERNAME'],'password':os.environ['PERMISSION_ADMIN_PASSWORD']})['accessToken']
    assert http('/api/platform/self/sso','POST',{},admin,opener=browser)[0]==200
    oa=oidc('oa-demo',admin);record('OIDC OA PKCE, ID-token signature and code replay rejection')
    hr=oidc('hr-demo',admin);record('OIDC HR second-client SSO without password re-entry')
    # Fresh isolated application records; no actual company data is modified.
    suffix=secrets.token_hex(4);password='Demo-'+secrets.token_hex(10)+'9'
    department=api('/api/platform/organization/departments','POST',{'name':'HTTP演示部门'+suffix},admin)['id']
    post=api('/api/platform/organization/posts','POST',{'name':'HTTP演示岗位'+suffix,'code':'http_'+suffix,'departmentId':department},admin)
    join={'action':'JOIN','username':'http_'+suffix,'displayName':'HTTP演示员工','departmentId':department,'postId':post['id'],'phone':'13900000099','company':'演示公司'}
    signed('hr',join,'join_'+suffix);assert signed('hr',join,'join_'+suffix)['reused'];record('HR join and replay idempotency')
    user=next(r for r in api('/api/users',token=admin) if r['username']==join['username'])['id']
    api(f'/api/platform/users/{user}/password','POST',{'password':password},admin)
    ordinary=api('/api/auth/login','POST',{'username':join['username'],'password':password})['accessToken']
    api('/api/demo/expenses',token=ordinary,expect=403)
    api('/api/platform/self/password','POST',{'oldPassword':password,'newPassword':password+'X'},ordinary)
    ordinary=api('/api/auth/login','POST',{'username':join['username'],'password':password+'X'})['accessToken'];record('Initial password change gate and re-login')
    application=api('/api/platform/self/applications','POST',{'roleId':post['roleId']},ordinary)
    signed('oa',{'approvalId':application['id'],'decision':'APPROVED'},'approve_'+suffix)
    assert len(api(f'/api/users/{user}/roles',token=admin))==1;record('OA approved callback grants requested role')
    signed('hr',{'action':'TRANSFER','userId':user,'departmentId':department,'postId':post['id'],'phone':'13900000099','company':'演示公司','roleIds':[]},'transfer_'+suffix)
    assert len(api(f'/api/users/{user}/roles',token=admin))==0;record('HR transfer atomically replaces grants')
    signed('hr',{'action':'LEAVE','userId':user},'leave_'+suffix);api('/api/platform/self',token=ordinary,expect=401);record('HR leave disables account and revokes JWT session')
    Path('output').mkdir(exist_ok=True);Path('output/phase-one-http-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(results,ensure_ascii=False,indent=2))
