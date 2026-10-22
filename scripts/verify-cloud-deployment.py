"""Explicit live acceptance (creates synthetic records), not a unit-test suite.
Usage: python verify-cloud-deployment.py --config /private/runtime.json --ca /path/ca.crt
For HTTP course demos, omit --ca. For HTTPS, pass the trusted CA file.
The config requires issuer, admin_username, admin_password, hr_secret, oa_secret,
client_secret. Only aggregate checks are written to evidence. No credentials/tokens.
"""
import argparse,base64,hashlib,hmac,http.cookiejar,importlib.util,json,re,secrets,ssl,time
from pathlib import Path
from urllib.request import Request,build_opener,HTTPCookieProcessor,HTTPRedirectHandler,HTTPSHandler
from urllib.error import HTTPError,URLError
from urllib.parse import urlencode,urlparse,parse_qs
from html import unescape

class StopCallback(HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        if '/oa-client/callback' in newurl or '/hr-client/callback' in newurl:return None
        return super().redirect_request(req,fp,code,msg,headers,newurl)
class Acceptance:
    def __init__(self,config,ca,output):
        self.c=config;self.base=config['issuer'].rstrip('/');self.ctx=ssl.create_default_context(cafile=ca);self.output=Path(output);self.results=[];self.suffix=secrets.token_hex(4)
        self.browser=build_opener(HTTPSHandler(context=self.ctx),HTTPCookieProcessor(http.cookiejar.CookieJar()),StopCallback())
    def http(self,path,method='GET',body=None,token=None,headers=None,form=False,browser=False):
        headers=dict(headers or {});headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
        if token:headers['Authorization']='Bearer '+token
        data=None if body is None else (urlencode(body) if form else json.dumps(body)).encode()
        req=Request(path if path.startswith(('http://','https://')) else self.base+path,data=data,method=method,headers=headers)
        for attempt in range(3):
            try:
                with (self.browser if browser else build_opener(HTTPSHandler(context=self.ctx))).open(req,timeout=30) as r:return r.status,r.read().decode(),dict(r.headers),r.url
            except HTTPError as e:return e.code,e.read().decode(),dict(e.headers),e.url
            except URLError as e:
                # A TLS handshake timeout precedes sending HTTP bytes: retrying cannot duplicate a mutation.
                if 'handshake operation timed out' not in str(e) or attempt==2:raise
    def api(self,path,method='GET',body=None,token=None,expect=200,**kw):
        status,text,headers,url=self.http(path,method,body,token,**kw)
        if status!=expect:
            detail={}
            try:
                error=json.loads(text);detail={k:error[k] for k in ('error','error_description','message') if k in error}
            except ValueError:pass
            raise AssertionError('%s %s: expected %s, got %s; %s'%(method,path,expect,status,detail))
        return json.loads(text) if text else None
    def record(self,name):
        self.results.append({'check':name,'result':'PASS'});self.save();print('PASS '+name,flush=True)
    def save(self):
        self.output.parent.mkdir(parents=True,exist_ok=True)
        self.output.write_text(json.dumps({'scope':'real '+urlparse(self.base).scheme.upper()+' / MySQL / contract callers; no external OA/HR product','run':self.suffix,'checks':self.results},ensure_ascii=False,indent=2))
    def login(self,user,password):return self.api('/api/auth/login','POST',{'username':user,'password':password})['accessToken']
    def refresh(self,token):return self.api('/api/auth/refresh','POST',token=token)['accessToken']
    def signed(self,source,event,rid=None,expect=200,bad=False):
        rid=rid or self.suffix+'_'+secrets.token_hex(5);stamp=str(int(time.time()));raw=json.dumps(event)
        sig=hmac.new(self.c[source+'_secret'].encode(),(source+'\n'+stamp+'\n'+rid+'\n'+raw).encode(),hashlib.sha256).hexdigest()
        return self.api('/api/integration/'+source,'POST',event,expect=expect,headers={'X-Request-Id':rid,'X-Timestamp':stamp,'X-Signature':'0'*64 if bad else sig})
    def identity(self,kind,external):
        rows=self.api('/api/platform/sync/identities?'+urlencode({'kind':kind,'size':100}),token=self.admin)
        return next(x['local_id'] for x in rows if x['external_id']==external)
    def oauth_exchange(self,client,payload,expect=200):
        auth=base64.b64encode((client+':'+self.c['client_secret']).encode()).decode()
        return self.api('/oauth2/token','POST',payload,expect=expect,form=True,headers={'Authorization':'Basic '+auth})
    def oidc(self,client,prefix):
        verifier=secrets.token_urlsafe(48);state=secrets.token_urlsafe(24);nonce=secrets.token_urlsafe(24);redirect=self.base+prefix+'callback'
        query={'client_id':client,'response_type':'code','redirect_uri':redirect,'scope':'openid profile','state':state,'nonce':nonce,'code_challenge':base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode(),'code_challenge_method':'S256'}
        status,text,headers,url=self.http('/oauth2/authorize?'+urlencode(query),browser=True,headers={'Accept':'text/html'})
        assert '/login' not in url,'SSO session was lost'
        if status==200:
            assert '/consent' in url,'Expected consent page'
            csrf=unescape(re.search(r'name=[\"\']_csrf[\"\'] value=[\"\']([^\"\']+)',text)[1]);consent_state=parse_qs(urlparse(url).query)['state'][0]
            status,text,headers,url=self.http('/oauth2/authorize','POST',{'client_id':client,'state':consent_state,'scope':'profile','_csrf':csrf},form=True,browser=True)
        assert status==302,'Authorization did not redirect to registered client'
        q=parse_qs(urlparse(headers['Location']).query);assert q.get('state')==[state] and 'code' in q
        payload={'grant_type':'authorization_code','code':q['code'][0],'redirect_uri':redirect,'code_verifier':verifier}
        tokens=self.oauth_exchange(client,payload)
        spec=importlib.util.spec_from_file_location('cloud_client',Path(__file__).with_name('oidc-cloud-client.py'))
        import os
        os.environ.update(OIDC_ISSUER=self.base,PERMISSION_OIDC_CLIENT_SECRET=self.c['client_secret'])
        if self.ca:os.environ['OIDC_CA_FILE']=self.ca
        else:os.environ.pop('OIDC_CA_FILE',None)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        claims=module.verify(tokens['id_token'],client,nonce,self.api('/.well-known/openid-configuration'));assert claims['sub']
        assert tokens.get('refresh_token')
        info=self.api('/userinfo',token=tokens['access_token']);assert 'permission_version' in info and info['permissions']
        perms=self.api('/api/oidc/permissions?clientId='+client,token=tokens['access_token']);assert perms['permissionVersion']==info['permission_version']
        other='cdwy-hr-check' if client=='cdwy-oa-check' else 'cdwy-oa-check'
        self.api('/api/oidc/permissions?clientId='+other,token=tokens['access_token'],expect=401)
        directory=self.api('/api/oidc/directory/users?clientId='+client,token=tokens['access_token']);assert 'phone' not in json.dumps(directory)
        self.record(client+' PKCE / consent / state / nonce / RSA signature / audience isolation / userinfo')
        return tokens,payload
    def run(self,ca):
        self.ca=ca;self.admin=self.login(self.c['admin_username'],self.c['admin_password']);a=self.admin
        assert self.http('/ui/index.html')[0]==200
        self.api('/api/platform/self',expect=401)
        env=self.api('/api/platform/self',token=a,headers={'Accept':'application/vnd.cdwy.v2+json'});assert env['data']['superAdmin'] and 'code' in env
        self.record('Public '+urlparse(self.base).scheme.upper()+' / MySQL login / anonymous denial / v2 envelope')
        sid=self.suffix;dept_ext='dept_'+sid;post_ext='post_'+sid;user_ext='emp_'+sid
        batch={'action':'SYNC','mode':'FULL','records':[{'action':'DEPARTMENT','externalId':dept_ext,'name':'云验收部门'+sid},{'action':'POST','externalId':post_ext,'name':'云验收岗位'+sid},{'action':'JOIN','externalId':user_ext,'displayName':'李明','departmentExternalId':dept_ext,'postExternalId':post_ext,'phone':'13900000123','company':'接口验收虚拟公司'}]}
        self.signed('hr',batch,'batch_'+sid);assert self.signed('hr',batch,'batch_'+sid)['reused']
        self.signed('hr',dict(batch,mode='INCREMENTAL'),'batch_'+sid,expect=409)
        self.signed('hr',batch,bad=True,expect=403)
        d=self.identity('DEPARTMENT',dept_ext);p=self.identity('POST',post_ext);u=self.identity('EMPLOYEE',user_ext);self.user=u;self.dept=d;self.post=p
        role=next(x['role_id'] for x in self.api('/api/company/posts',token=a) if x['id']==p);self.role=role
        user=next(x for x in self.api('/api/users',token=a) if x['id']==u);self.username=user['username'];assert self.username.startswith('lm')
        assert self.api('/api/users/%s/roles'%u,token=a)==[]
        self.record('HR FULL dependency mapping / pinyin account / no implicit role / signed idempotency and mismatch rejection')
        failed_ext='rollback_'+sid
        self.signed('hr',{'action':'SYNC','mode':'INCREMENTAL','records':[{'action':'DEPARTMENT','externalId':failed_ext,'name':'回滚部门'+sid},{'action':'JOIN','externalId':'bad_'+sid}]},'fail_'+sid,expect=400)
        rows=self.api('/api/platform/sync/identities?kind=DEPARTMENT&size=100',token=a);assert not any(x['external_id']==failed_ext for x in rows)
        failures=self.api('/api/platform/sync/failures',token=a);assert 'fail_'+sid in json.dumps(failures)
        rec=self.signed('hr',{'action':'RECONCILE','records':[{'externalId':user_ext}]});assert rec['detail']['matched']==1
        self.record('HR failed batch rolls back / durable failure queue / reconciliation')
        issued=self.api('/api/platform/initial-passwords','POST',{'departmentId':d},a)['issued'];assert len(issued)==1
        password=issued[0]['initialPassword'];t=self.login(self.username,password)
        self.api('/api/platform/self/portal',token=t,expect=403)
        self.api('/api/platform/self/password','POST',{'oldPassword':password,'newPassword':password+'X'},t)
        self.api('/api/platform/self',token=t,expect=401)
        self.user_password=password+'X';t=self.login(self.username,self.user_password);self.user_token=t
        assert self.api('/api/platform/initial-passwords','POST',{'departmentId':d},a)['issued']==[]
        self.api('/api/platform/users',token=t,expect=403)
        self.record('Department initial password issued once / forced change / revoked old session / ordinary-user boundary')
        rid='oa_'+sid
        self.signed('oa',{'action':'APPLICATION','requestId':rid,'userId':u,'roleIds':[role]})
        assert self.signed('oa',{'action':'QUERY_APPLICATION_STATUS','requestId':rid})['detail']['application']['decision']=='PENDING'
        self.signed('oa',{'action':'RESULT','requestId':rid,'decision':'APPROVED'},'approved_'+sid)
        assert self.signed('oa',{'action':'RESULT','requestId':rid,'decision':'APPROVED'},'approved_'+sid)['reused']
        self.signed('oa',{'action':'RESULT','requestId':rid,'decision':'REJECTED'},expect=409)
        self.api('/api/platform/self',token=t,expect=401);t=self.refresh(t);self.user_token=t
        assert len(self.api('/api/users/%s/roles'%u,token=a))==1
        assert any(x['request_id']==rid for x in self.api('/api/platform/self/applications',token=t))
        assert self.http('/api/platform/self/applications','POST',{'roleId':role},t)[0] in (404,405)
        self.signed('oa',{'action':'QUERY_PERMISSIONS','userId':u})
        for decision in ('REJECTED','CANCELLED'):
            request=decision.lower()+'_'+sid
            self.signed('oa',{'action':'APPLICATION','requestId':request,'userId':u,'roleIds':[role]})
            self.signed('oa',{'action':'RESULT','requestId':request,'decision':decision})
        self.record('OA application/status/approval/rejection/cancellation / role-version refresh / old platform POST removed, GET retained')
        self.api('/api/platform/reviews','POST',{'userId':u,'roleId':role,'decision':'KEEP','reason':'云验收保留'},a)
        self.api('/api/platform/reviews','POST',{'userId':u,'roleId':role,'decision':'REVOKE','reason':'云验收回收'},a)
        assert self.api('/api/users/%s/roles'%u,token=a)==[]
        self.api('/api/users/%s/roles/%s'%(u,role),'PUT',token=a,expect=204)
        t=self.refresh(t);self.user_token=t
        self.record('Permission review KEEP / transactional REVOKE / review history')
        from datetime import datetime,timedelta,timezone
        expiry=(datetime.now(timezone(timedelta(hours=8)))+timedelta(days=3)).date().isoformat()
        ext=self.api('/api/platform/external-users','POST',{'username':'ext_'+sid,'displayName':'云验收临时人员','personType':'TEMPORARY','expiresOn':expiry,'departmentId':d,'postId':p,'phone':'13800000999','company':'验收虚拟公司','password':'Cloud!'+sid+'9a'},a)
        row=next(x for x in self.api('/api/platform/external-users',token=a) if x['id']==ext['id']);assert row['days_left']==3 and row['status']=='EXPIRING'
        self.api('/api/platform/users/%s/state'%ext['id'],'PUT',{'enabled':False},a)
        self.record('External-person MySQL account join / UTC expiry conversion / three-day reminder')
        other=self.api('/api/platform/organization/departments','POST',{'name':'其他部门'+sid},a)['id']
        self.api('/api/platform/administrators/%s'%u,'PUT',{'departmentId':d},a)
        self.api('/api/platform/initial-passwords?departmentId='+str(other),token=t,expect=403)
        self.api('/api/platform/administrators/%s/%s'%(u,d),'DELETE',token=a)
        self.api('/api/roles/%s'%role,'DELETE',token=a,expect=409)
        self.record('Department administrator cross-department denial / referenced role deletion blocked')
        self.apps=[]
        for client,prefix in [('cdwy-oa-check','/oa-client/'),('cdwy-hr-check','/hr-client/')]:
            existing=next((x for x in self.api('/api/systems',token=a) if x['code']==client),None)
            system=existing or self.api('/api/systems','POST',{'code':client,'name':client+' 接口验收调用端','baseUrl':self.base+prefix},a,expect=201)
            system_id=system['id'];self.apps.append(system_id)
            self.api('/api/platform/applications/%s/config'%system_id,'PUT',{'clientId':client,'entryUrl':self.base+prefix,'protocol':'OIDC','redirectUris':[self.base+prefix+'callback'],'clientSecret':self.c['client_secret']},a)
            assert not any(x['system_id']==system_id for x in self.api('/api/platform/self/portal',token=a))
            # Gate activation is prerequisite to exercising the isolated synthetic caller.
            self.api('/api/platform/applications/%s/acceptance'%system_id,'POST',{'login':True,'callback':True,'permission':True,'revocation':True,'isolation':True,'evidence':'隔离的接口契约验收调用端；验收执行中，完成记录见 cloud-verification.json；非外部 OA/HR 产品。'},a)
            perm=self.api('/api/permissions','POST',{'systemId':system_id,'code':client+':check:'+sid,'name':'云验收权限','menuPath':prefix,'type':'API'},a,expect=201)
            self.api('/api/roles/%s/permissions/%s'%(role,perm['id']),'PUT',token=a,expect=204)
        self.api('/api/platform/self',token=t,expect=401);t=self.refresh(t);self.user_token=t
        assert len(self.api('/api/platform/self/portal',token=t))==2
        assert self.http('/api/platform/self/sso','POST',{},t,browser=True)[0]==200
        oa,payload=self.oidc('cdwy-oa-check','/oa-client/');hr,_=self.oidc('cdwy-hr-check','/hr-client/');self.oa=oa;self.hr=hr
        # Real reverse-proxy/client callback, which independently verifies and consumes the authorization code.
        for prefix in ('/oa-client/','/hr-client/'):
            status,text,headers,url=self.http(prefix+'login',browser=True)
            assert status==302 and 'callback' in headers.get('Location',''), 'Existing consent should allow SSO'
            status,text,headers,url=self.http(headers['Location'],browser=True)
            assert status==200 and '登录成功' in text,'Contract caller did not verify callback'
        self.record('Two deployed OIDC callers complete real callback and second-application SSO without password re-entry')
        self.oauth_exchange('cdwy-oa-check',payload,expect=400)
        self.api('/userinfo',token=oa['access_token'],expect=401)
        self.record('Authorization code replay rejected and issued access revoked')
        old=hr['refresh_token'];renewed=self.oauth_exchange('cdwy-hr-check',{'grant_type':'refresh_token','refresh_token':old});assert renewed['refresh_token']!=old
        self.oauth_exchange('cdwy-hr-check',{'grant_type':'refresh_token','refresh_token':old},expect=400);self.hr=renewed
        self.record('Confidential client BASIC authentication / refresh-token rotation and replay rejection')
        # Persist sensitive continuation only in process memory; an optional caller can restart the service here.
        self.before_restart_kids=[k['kid'] for k in self.api('/oauth2/jwks')['keys']]
        return self
    def finish(self):
        a=self.admin;u=self.user;t=self.user_token
        self.api('/api/platform/self',token=t)
        assert [k['kid'] for k in self.api('/oauth2/jwks')['keys']]==self.before_restart_kids
        self.hr=self.oauth_exchange('cdwy-hr-check',{'grant_type':'refresh_token','refresh_token':self.hr['refresh_token']})
        self.oidc('cdwy-hr-check','/hr-client/')
        self.record('Service restart preserves platform login, JDBC browser session, OIDC authorizations and signing key')
        self.signed('hr',{'action':'TRANSFER','userId':u,'departmentId':self.dept,'postId':self.post,'phone':'13900000123','company':'接口验收虚拟公司','roleIds':[]})
        assert self.api('/api/users/%s/roles'%u,token=a)==[]
        self.api('/api/platform/self',token=t,expect=401)
        self.api('/userinfo',token=self.hr['access_token'],expect=401)
        fresh=self.oauth_exchange('cdwy-hr-check',{'grant_type':'refresh_token','refresh_token':self.hr['refresh_token']})
        self.api('/userinfo',token=fresh['access_token'])
        self.api('/api/platform/users/%s/offline'%u,'POST',token=a)
        self.oauth_exchange('cdwy-hr-check',{'grant_type':'refresh_token','refresh_token':fresh['refresh_token']},expect=400)
        self.record('Transfer atomically removes roles / old tokens invalid / refreshed claims current / forced offline revokes refresh')
        t=self.login(self.username,self.user_password)
        self.signed('hr',{'action':'LEAVE','userId':u})
        self.api('/api/platform/self',token=t,expect=401)
        self.api('/api/auth/login','POST',{'username':self.username,'password':self.user_password},expect=401)
        self.record('HR leave disables account and invalidates all sessions')
        for system_id in self.apps:
            self.api('/api/platform/applications/%s/acceptance'%system_id,'POST',{'login':True,'callback':True,'permission':True,'revocation':True,'isolation':True,'evidence':'真实 '+urlparse(self.base).scheme.upper()+'/MySQL 上的接口契约调用端通过 PKCE、签名、回调、免登、权限版本和下线验证。记录：cloud-verification.json。没有接入外部 OA/HR 产品。'},a)
        self.save()
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--ca');p.add_argument('--output',default='output/cloud-verification.json');args=p.parse_args()
    check=Acceptance(json.loads(Path(args.config).read_text()),args.ca,args.output)
    check.run(args.ca)
    # No deployment action is hidden in this reusable script.
    input('Restart the service now, then press Enter to verify persistence: ')
    check.finish()
