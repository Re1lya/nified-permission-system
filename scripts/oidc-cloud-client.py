"""Two contract callers for deployment acceptance; these are not OA/HR products.
The reverse proxy selects HTTP for course demos or HTTPS for normal deployments. Secrets come only from the service environment.
Python 3.6+ and cryptography are required; callback URLs/codes/tokens are never logged.
"""
import base64, hashlib, html, json, os, secrets, ssl, threading, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from http.cookies import SimpleCookie
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import urlopen, Request
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes

ISSUER=os.environ['OIDC_ISSUER'].rstrip('/')
BACKCHANNEL=os.environ.get('OIDC_BACKCHANNEL',ISSUER).rstrip('/')
SECURE_COOKIE=urlparse(ISSUER).scheme=='https'
SECRET=os.environ['PERMISSION_OIDC_CLIENT_SECRET']
CONTEXT=ssl.create_default_context(cafile=os.environ.get('OIDC_CA_FILE'))
def b64(value):return base64.urlsafe_b64encode(value).rstrip(b'=').decode()
def decode(value):return base64.urlsafe_b64decode(value+'='*(-len(value)%4))
def fetch(url,data=None,client=None,token=None):
    if not url.startswith(ISSUER+'/'):raise ValueError('Unexpected issuer endpoint')
    headers={}
    if client:headers['Authorization']='Basic '+base64.b64encode((client+':'+SECRET).encode()).decode()
    if token:headers['Authorization']='Bearer '+token
    req=Request(BACKCHANNEL+url[len(ISSUER):],data=None if data is None else urlencode(data).encode(),headers=headers)
    with urlopen(req,context=CONTEXT,timeout=20) as r:return json.load(r)
def verify(token,client,nonce,metadata):
    header,payload,sig=token.split('.');hdr=json.loads(decode(header));claims=json.loads(decode(payload))
    if hdr.get('alg')!='RS256':raise ValueError('Unexpected algorithm')
    jwk=next(k for k in fetch(metadata['jwks_uri'])['keys'] if k['kid']==hdr['kid'])
    public=rsa.RSAPublicNumbers(int.from_bytes(decode(jwk['e']),'big'),int.from_bytes(decode(jwk['n']),'big')).public_key(default_backend())
    public.verify(decode(sig),(header+'.'+payload).encode(),padding.PKCS1v15(),hashes.SHA256())
    aud=claims.get('aud',[]);aud=[aud] if isinstance(aud,str) else aud
    if claims.get('iss')!=ISSUER or client not in aud or claims.get('nonce')!=nonce or claims.get('exp',0)<=time.time():raise ValueError('Invalid claims')
    return claims
class Server(ThreadingMixIn,HTTPServer):daemon_threads=True

def handler(client,prefix):
    sessions={};cookie_name=('contract_' if SECURE_COOKIE else 'contract_http_')+client.replace('-','_')
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def page(self,title,body,status=200):
            data=('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>'+html.escape(title)+'</title><style>body{font:18px sans-serif;max-width:820px;margin:64px auto;line-height:1.8;background:#f5f8f6;color:#243b30}main{padding:32px;background:white}a{color:#246344}</style><main><p>统一权限管理 · 接口契约调用端</p><h1>'+html.escape(title)+'</h1>'+body+'</main></html>').encode()
            self.send_response(status);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
        def redirect(self,url,cookie=None):
            self.send_response(302)
            if cookie:self.send_header('Set-Cookie',cookie)
            self.send_header('Location',url);self.send_header('Cache-Control','no-store');self.end_headers()
        def do_GET(self):
            try:
                now=time.time()
                for key in list(sessions):
                    if sessions[key]['expires']<now:del sessions[key]
                cookie=SimpleCookie(self.headers.get('Cookie','')).get(cookie_name);s=sessions.get(cookie.value) if cookie else None
                path=urlparse(self.path)
                if path.path==prefix+'login':
                    metadata=fetch(ISSUER+'/.well-known/openid-configuration')
                    if metadata['issuer']!=ISSUER:raise ValueError('Issuer mismatch')
                    sid=secrets.token_urlsafe(24);verifier=secrets.token_urlsafe(48);state=secrets.token_urlsafe(24);nonce=secrets.token_urlsafe(24)
                    sessions[sid]={'expires':now+600,'verifier':verifier,'state':state,'nonce':nonce,'metadata':metadata}
                    query={'response_type':'code','client_id':client,'redirect_uri':ISSUER+prefix+'callback','scope':'openid profile','state':state,'nonce':nonce,'code_challenge':b64(hashlib.sha256(verifier.encode()).digest()),'code_challenge_method':'S256'}
                    self.redirect(metadata['authorization_endpoint']+'?'+urlencode(query),cookie_name+'='+sid+('; Secure' if SECURE_COOKIE else '')+'; HttpOnly; SameSite=Lax; Path='+prefix);return
                if path.path==prefix+'callback':
                    q=parse_qs(path.query)
                    if not s or not secrets.compare_digest(q.get('state',[''])[0],s.pop('state','invalid')):raise ValueError('Invalid state')
                    result=fetch(s['metadata']['token_endpoint'],{'grant_type':'authorization_code','code':q['code'][0],'redirect_uri':ISSUER+prefix+'callback','code_verifier':s.pop('verifier')},client)
                    claims=verify(result['id_token'],client,s.pop('nonce'),s['metadata']);s['subject']=claims['sub'];s['expires']=claims['exp'];s['access']=result['access_token']
                    self.redirect(prefix);return
                if path.path!=prefix:self.page('页面不存在','',404);return
                if s and 'subject' in s:
                    # Re-check server authority on every page: stale client sessions must not imply current access.
                    info=fetch(ISSUER+'/userinfo',token=s['access'])
                    self.page(client+' 登录成功','<p>已校验 PKCE、state、签名、issuer、audience、nonce 和令牌有效期。</p><p>账号编号：'+html.escape(str(s['subject']))+'</p><p>权限版本：'+html.escape(str(info.get('permission_version')))+'</p><p>仅验证认证与接口契约，不代表已接入外部 OA/HR 产品。</p><a href="'+('/hr-client/' if prefix=='/oa-client/' else '/oa-client/')+'">打开另一个调用端验证单点登录</a>')
                else:self.page(client,'<p>用于真实部署的接口流程验收，不是外部 OA/HR 产品。</p><a href="'+prefix+'login">使用统一账号登录</a>')
            except Exception:self.page('认证未完成','<p>授权已失效或验证失败，请重新登录。</p><a href="'+prefix+'login">重新登录</a>',400)
    return Handler
if __name__=='__main__':
    servers=[Server(('127.0.0.1',port),handler(client,prefix)) for client,prefix,port in [('cdwy-oa-check','/oa-client/',18101),('cdwy-hr-check','/hr-client/',18102)]]
    for server in servers:threading.Thread(target=server.serve_forever,daemon=True).start()
    while True:time.sleep(60)
