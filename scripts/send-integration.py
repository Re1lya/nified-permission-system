"""Send a local signed OA/HR event. Credentials are supplied through environment only."""
import argparse, hashlib, hmac, json, os, time
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.parse import urlparse
from urllib.error import HTTPError

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',choices=['oa','hr']);p.add_argument('request_id');p.add_argument('file',type=Path);p.add_argument('--base',default='http://127.0.0.1:18081');a=p.parse_args()
    if urlparse(a.base).hostname not in ('localhost','127.0.0.1'):raise SystemExit('仅允许本地演示地址')
    key=os.environ.get('PERMISSION_'+a.source.upper()+'_SECRET','')
    if len(key)<32:raise SystemExit('请设置对应的 PERMISSION_HR_SECRET 或 PERMISSION_OA_SECRET 环境变量，至少 32 字符')
    body=a.file.read_text(encoding='utf-8-sig');json.loads(body);timestamp=str(int(time.time()))
    signature=hmac.new(key.encode(),(a.source+'\n'+timestamp+'\n'+a.request_id+'\n'+body).encode(),hashlib.sha256).hexdigest()
    request=Request(a.base+'/api/integration/'+a.source,data=body.encode(),headers={'Content-Type':'application/json','X-Request-Id':a.request_id,'X-Timestamp':timestamp,'X-Signature':signature})
    try:
        with urlopen(request) as response:print(response.status,response.read().decode())
    except HTTPError as e:print(e.code,e.read().decode());raise SystemExit(1)
