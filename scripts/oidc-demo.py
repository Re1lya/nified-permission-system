"""Two loopback-only OIDC clients. Requires cryptography, never prints tokens."""
import base64, hashlib, html, json, os, secrets, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import urlopen, Request
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes

ISSUER = os.environ.get('OIDC_ISSUER', 'http://127.0.0.1:18081')
SECRET = os.environ.get('PERMISSION_OIDC_CLIENT_SECRET')  # optional; enables the refresh-token demonstration
if urlparse(ISSUER).hostname not in ('127.0.0.1', 'localhost'):
    raise SystemExit('This demonstration only accepts a loopback issuer')
def b64(value): return base64.urlsafe_b64encode(value).rstrip(b'=').decode()
def decode(value): return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
def fetch(url, data=None):
    req = Request(url, data=urlencode(data).encode() if data else None)
    with urlopen(req, timeout=15) as response: return json.load(response)
def verify(token, client, nonce, metadata):
    header, payload, sig = token.split('.')
    hdr, claims = json.loads(decode(header)), json.loads(decode(payload))
    if hdr.get('alg') != 'RS256': raise ValueError('Unexpected signing algorithm')
    keys = fetch(metadata['jwks_uri'])['keys']
    jwk = next(k for k in keys if k['kid'] == hdr['kid'])
    public = rsa.RSAPublicNumbers(int.from_bytes(decode(jwk['e']), 'big'), int.from_bytes(decode(jwk['n']), 'big')).public_key()
    public.verify(decode(sig), (header+'.'+payload).encode(), padding.PKCS1v15(), hashes.SHA256())
    audience = claims.get('aud', [])
    if isinstance(audience, str): audience = [audience]
    if claims.get('iss') != ISSUER or client not in audience or claims.get('nonce') != nonce or claims.get('exp', 0) <= time.time():
        raise ValueError('Invalid issuer, audience, nonce or expiry')
    return claims

def handler(client, port):
    sessions = {}
    cookie_name='demo_session_'+client.replace('-','_')
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass  # callback codes and state must not be logged
        def page(self, title, content, status=200):
            body = ('<!doctype html><meta charset="utf-8"><title>'+html.escape(title)+'</title><style>body{font:18px "Microsoft YaHei",sans-serif;background:#f5f8f6;color:#243b30;max-width:800px;margin:70px auto;line-height:1.8}a{color:#246344}section{background:#fcfdfc;padding:32px;border:1px solid #cbd9cf}h1{font-size:30px}</style><section><p>统一权限管理 · 本地客户端演示</p><h1>'+html.escape(title)+'</h1>'+content+'</section>').encode()
            self.send_response(status);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
        def do_GET(self):
            try:
                for key in list(sessions):
                    if sessions[key]['expires'] < time.time(): del sessions[key]
                cookie=SimpleCookie(self.headers.get('Cookie','')); sid=cookie.get(cookie_name);session=sessions.get(sid.value) if sid else None
                path=urlparse(self.path)
                if path.path=='/login':
                    metadata=fetch(ISSUER+'/.well-known/openid-configuration')
                    sid=secrets.token_urlsafe(24); verifier=secrets.token_urlsafe(48);state=secrets.token_urlsafe(24);nonce=secrets.token_urlsafe(24)
                    sessions[sid]={'verifier':verifier,'state':state,'nonce':nonce,'expires':time.time()+600,'metadata':metadata}
                    params={'response_type':'code','client_id':client,'redirect_uri':f'http://127.0.0.1:{port}/callback','scope':'openid profile','state':state,'nonce':nonce,'code_challenge':b64(hashlib.sha256(verifier.encode()).digest()),'code_challenge_method':'S256'}
                    self.send_response(302);self.send_header('Set-Cookie',f'{cookie_name}={sid}; HttpOnly; SameSite=Lax; Path=/');self.send_header('Location',metadata['authorization_endpoint']+'?'+urlencode(params));self.end_headers();return
                if path.path=='/callback':
                    params=parse_qs(path.query)
                    if not session or not secrets.compare_digest(params.get('state',[''])[0],session['state']): raise ValueError('Invalid or expired state')
                    state=session.pop('state')  # one-time callback
                    exchange={'grant_type':'authorization_code','client_id':client,'code':params['code'][0],'redirect_uri':f'http://127.0.0.1:{port}/callback','code_verifier':session.pop('verifier')}
                    if SECRET:exchange['client_secret']=SECRET
                    result=fetch(session['metadata']['token_endpoint'],exchange);session['refresh']=result.get('refresh_token')
                    claims=verify(result['id_token'],client,session.pop('nonce'),session['metadata']);session['subject']=claims['sub'];session['expires']=claims['exp']
                    self.send_response(302);self.send_header('Location','/');self.end_headers();return
                if path.path=='/refresh' and session and session.get('refresh'):
                    result=fetch(session['metadata']['token_endpoint'],{'grant_type':'refresh_token','client_id':client,'client_secret':SECRET,'refresh_token':session['refresh']})
                    session['refresh']=result.get('refresh_token');claims=json.loads(decode(result['access_token'].split('.')[1]))
                    self.page(client+' 令牌已刷新','<p>使用刷新令牌换取了新的访问令牌，旧刷新令牌已作废。</p><p>当前权限版本号：'+html.escape(str(claims.get('permission_version')))+'</p><a href="/">返回</a>');return
                if path.path!='/': self.page('页面不存在','<a href="/">返回首页</a>',404);return
                if session and 'subject' in session:
                    self.page(client+' 登录成功','<p>已验证授权码、PKCE、签名、issuer、audience、nonce 与有效期。</p><p>统一账号编号：'+html.escape(str(session['subject']))+'</p><p>这里仅展示认证结果，不代表已获业务权限。</p><a href="http://127.0.0.1:'+str(18102 if port==18101 else 18101)+'/">打开另一个演示客户端验证免登</a>'+('<p><a href="/refresh">使用刷新令牌更新访问令牌</a></p>' if session.get('refresh') else ''))
                else:self.page(client,'<p>通过统一认证中心登录本地演示客户端。不会连接真实 OA 或 HR。</p><a href="/login">使用统一账号登录</a>')
            except Exception:
                self.page('认证未完成','<p>状态已失效、授权被拒绝或验证失败。请重新发起登录。</p><a href="/login">重新登录</a>',400)
    return Handler

if __name__=='__main__':
    servers=[ThreadingHTTPServer(('127.0.0.1',port),handler(client,port)) for client,port in [('oa-demo',18101),('hr-demo',18102)]]
    for server in servers: threading.Thread(target=server.serve_forever,daemon=True).start()
    print('Local OIDC clients: http://127.0.0.1:18101 and http://127.0.0.1:18102',flush=True)
    try:
        while True:time.sleep(1)
    except KeyboardInterrupt:
        for server in servers:server.shutdown()
