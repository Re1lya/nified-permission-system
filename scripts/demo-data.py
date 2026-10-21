"""Prepare only reserved demo_* records through authenticated local HTTP APIs.
No third-party Python dependencies. Does not write passwords or tokens to evidence.
"""
import argparse
import getpass
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone


class Demo:
    def __init__(self, base, admin_name, admin_password, demo_password):
        parsed = urllib.parse.urlparse(base)
        if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1'):
            raise ValueError('本脚本只允许本机 HTTP 演示地址')
        self.base = base.rstrip('/')
        self.admin = self.login(admin_name, admin_password)
        self.password = demo_password
        if not self.call('GET', '/api/auth/me')['administrator']:
            raise ValueError('请使用首次初始化的管理员')

    def call(self, method, path, body=None, token=None, expected=None):
        token = self.admin if token is None and hasattr(self, 'admin') else token
        data = None if body is None else json.dumps(body).encode()
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        result = json.loads(raw) if raw else None
        if expected is not None:
            if status != expected:
                raise AssertionError(f'{method} {path}: expected {expected}, got {status}')
        elif status >= 400:
            raise RuntimeError(f'{method} {path}: {status} {result.get("message", "请求失败")}')
        return result

    def login(self, username, password):
        return self.call('POST', '/api/auth/login', {'username': username, 'password': password}, token='', expected=200)['accessToken']

    def upsert(self, resource, body, update=None):
        key = 'username' if resource == 'users' else 'code'
        rows = self.call('GET', '/api/' + resource)
        existing = next((r for r in rows if r[key] == body[key]), None)
        if existing:
            if update:
                self.call('PUT', f'/api/{resource}/{existing["id"]}', update)
            return existing['id']
        return self.call('POST', '/api/' + resource, body)['id']

    def seed(self):
        system = self.upsert('systems', {'code': 'demo_finance', 'name': '财务演示系统'},
                             {'name': '财务演示系统', 'enabled': True, 'baseUrl': '/api/demo/expenses'})
        viewer = self.upsert('roles', {'code': 'demo_viewer', 'name': '报销查看员'},
                             {'name': '报销查看员', 'description': '提供报销列表权限', 'enabled': True})
        reviewer = self.upsert('roles', {'code': 'demo_reviewer', 'name': '报销复核员'},
                               {'name': '报销复核员', 'description': '重复列表权限，加一项设计权限', 'enabled': True})
        expense = self.upsert('permissions', {'systemId': system, 'code': 'expense:list', 'name': '查看报销列表', 'menuPath': '/expenses'})
        existing = next(p for p in self.call('GET', '/api/permissions') if p['id'] == expense)
        if existing['systemId'] != system:
            raise ValueError('expense:list 已属于其他系统；请使用全新的 demo 内存数据库，不覆盖已有配置')
        extra = self.upsert('permissions', {'systemId': system, 'code': 'demo:detail', 'name': '查看明细（仅权限设计）', 'menuPath': '/demo/detail'})
        for role, desired in ((viewer, {expense}), (reviewer, {expense, extra})):
            for p in self.call('GET', f'/api/roles/{role}/permissions'):
                if p['id'] not in desired:
                    self.call('DELETE', f'/api/roles/{role}/permissions/{p["id"]}')
            for p in desired:
                self.call('PUT', f'/api/roles/{role}/permissions/{p}')
        ids = {}
        for username, display, roles in (('demo_alice', '演示用户 · 有权限', [viewer, reviewer]),
                                         ('demo_bob', '演示用户 · 无权限', [])):
            user = self.upsert('users', {'username': username, 'displayName': display}, {'displayName': display, 'enabled': True})
            self.call('PUT', f'/api/users/{user}/password', {'password': self.password})
            for r in self.call('GET', f'/api/users/{user}/roles'):
                self.call('DELETE', f'/api/users/{user}/roles/{r["id"]}')
            for r in roles:
                self.call('PUT', f'/api/users/{user}/roles/{r}')
            ids[username] = user
        return dict(system=system, viewer=viewer, reviewer=reviewer, expense=expense, **ids)

    def verify(self, ids):
        results = []
        def check(name, method, path, token, expected, body=None):
            value = self.call(method, path, body, token=token, expected=expected)
            results.append({'场景': name, '预期HTTP': expected, '实际HTTP': expected, '通过': True})
            return value
        user = ids['demo_alice']
        first = self.login('demo_alice', self.password)
        bob = self.login('demo_bob', self.password)
        check('管理员登录与管理列表', 'GET', '/api/users', self.admin, 200)
        check('普通用户有权限', 'GET', '/api/demo/expenses', first, 200)
        own = check('多角色权限并集', 'GET', '/api/auth/me', first, 200)
        assert {p['code'] for p in own['permissions']} == {'expense:list', 'demo:detail'}
        assert len(own['permissions']) == 2
        self.call('DELETE', f'/api/users/{user}/roles/{ids["viewer"]}')
        check('回收一个角色，另一角色保留相同权限，同一JWT仍成功', 'GET', '/api/demo/expenses', first, 200)
        self.call('DELETE', f'/api/users/{user}/roles/{ids["reviewer"]}')
        check('回收最后一个角色，同一JWT被拒绝', 'GET', '/api/demo/expenses', first, 403)
        check('无权限账号', 'GET', '/api/demo/expenses', bob, 403)
        check('普通用户读取管理API', 'GET', '/api/users', first, 403)
        check('普通用户修改他人密码', 'PUT', f'/api/users/{ids["demo_bob"]}/password', first, 403, {'password': self.password})
        check('普通用户给自己授权', 'PUT', f'/api/users/{user}/roles/{ids["viewer"]}', first, 403)
        check('普通用户创建管理员', 'POST', '/api/users', first, 403, {'username': 'forged', 'displayName': '伪造', 'administrator': True})
        check('注销普通用户', 'POST', '/api/auth/logout', first, 204)
        check('注销后旧JWT失效', 'GET', '/api/demo/expenses', first, 401)
        check('未登录管理API', 'GET', '/api/users', '', 401)
        check('错误表单', 'POST', '/api/roles', self.admin, 400, {'code': '', 'name': ''})
        self.call('POST', '/api/auth/logout', token=bob)
        return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--report', default='docs/first-check/evidence/http-verification.json')
    args = parser.parse_args()
    demo = Demo(args.base_url, os.getenv('PERMISSION_BOOTSTRAP_USERNAME') or input('管理员用户名: '),
                os.getenv('PERMISSION_BOOTSTRAP_PASSWORD') or getpass.getpass('管理员密码: '),
                os.getenv('PERMISSION_DEMO_PASSWORD') or getpass.getpass('为两个演示用户设置密码: '))
    try:
        ids = demo.seed()
        if args.verify:
            try:
                results = demo.verify(ids)
            finally:
                demo.seed()  # Restore the known presentation baseline even after a failed assertion.
            path = Path(args.report)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({'time': datetime.now(timezone.utc).isoformat(), 'database': 'H2 demo profile (local run)',
                                        'baseUrl': args.base_url, 'results': results}, ensure_ascii=False, indent=2), encoding='utf-8')
            print(f'真实 HTTP 验证 {len(results)} 项通过；报告: {path}')
        print('演示数据准备完成：demo_alice 有两个角色，demo_bob 无角色。密码为刚才输入值。')
    finally:
        demo.call('POST', '/api/auth/logout')


if __name__ == '__main__':
    main()
