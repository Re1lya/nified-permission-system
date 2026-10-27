raise SystemExit("此脚本基于历史 H2 快照，已停用以免覆盖当前材料。请运行 python3 scripts/build-er-assets.py 和 node scripts/render-er.cjs。")
"""Draw the complete ER diagram (from the exported H2 schema) and the UI navigation diagram.
Run after the tests have exported output/phase-one-h2.sql. Output: docs/phase-one/images/er-full.png, ui-navigation.png.
"""
from pathlib import Path
import re
from PIL import Image, ImageDraw, ImageFont
ROOT = Path(__file__).resolve().parents[1]; OUT = ROOT / 'docs/phase-one/images'
F = lambda size, bold=False: ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc', size)
INK = (32, 41, 52); LINE = (110, 122, 136); MUTED = (110, 120, 132); BG = (255, 255, 255)
GROUP_COLORS = {'org': (232, 242, 236), 'identity': (234, 240, 249), 'rbac': (250, 243, 229), 'catalog': (243, 237, 248), 'records': (241, 242, 244)}

def center(d, xy, text, font, fill=INK):
    w = d.textlength(text, font=font); d.text((xy[0] - w / 2, xy[1]), text, font=font, fill=fill)

# ---------- ER ----------
def er():
    sql = (ROOT / 'output/phase-one-h2.sql').read_text(encoding='utf-8')
    fks = [(a.lower(), c.lower(), b.lower()) for a, c, b in re.findall(r'ALTER TABLE "PUBLIC"\."([A-Z_]+)" ADD CONSTRAINT "PUBLIC"\."[A-Z0-9_]+" FOREIGN KEY\("([A-Z_]+)"\) REFERENCES "PUBLIC"\."([A-Z_]+)"', sql)]
    tables = sorted(set(m.lower() for m in re.findall(r'CREATE (?:CACHED |MEMORY )?TABLE "PUBLIC"\."([A-Z_]+)"', sql)))
    layout = {  # column, row, group
        'sys_department': (0, 0, 'org'), 'admin_department': (0, 1, 'org'), 'department_permission': (0, 2, 'org'), 'managed_role': (0, 3, 'org'), 'sys_post': (0, 4, 'org'), 'employee_profile': (0, 5, 'org'),
        'sys_user': (1, 0, 'identity'), 'account_policy': (1, 1, 'identity'), 'sys_login_session': (1, 2, 'identity'), 'session_activity': (1, 3, 'identity'), 'login_attempt': (1, 4, 'identity'), 'approval_record': (1, 5, 'records'),
        'sys_user_role': (2, 0, 'rbac'), 'sys_role': (2, 1, 'rbac'), 'sys_role_permission': (2, 2, 'rbac'), 'sys_permission': (2, 3, 'rbac'), 'biz_system': (2, 4, 'rbac'), 'integration_event': (2, 5, 'records'),
        'sys_module': (3, 0, 'catalog'), 'sys_function': (3, 1, 'catalog'), 'permission_detail': (3, 2, 'catalog'), 'permission_template': (3, 3, 'catalog'), 'company_import_batch': (3, 4, 'catalog'), 'audit_event': (3, 5, 'records'),
        'data_dictionary': (4, 5, 'records'),
    }
    missing = [t for t in tables if t not in layout]
    assert not missing, ('tables without a position', missing)
    W, H = 2600, 1700; cw, rh, bw, bh = 500, 250, 360, 0
    img = Image.new('RGB', (W, H), BG); d = ImageDraw.Draw(img)
    d.text((40, 24), f'完整 ER 图：{len(tables)} 张表、{len(fks)} 个外键', font=F(36, True), fill=INK)
    d.text((40, 76), '每张表只出现一次；框内列出主键与外键列。连线从外键表指向被引用表（多 → 一）。数据来源：测试导出的实际表结构。', font=F(20), fill=MUTED)
    lx = 1500
    for key, label in [('org', '组织与范围'), ('identity', '身份与会话'), ('rbac', 'RBAC0 授权'), ('catalog', '功能目录与导入'), ('records', '审批、集成与审计')]:
        d.rounded_rectangle([lx, 82, lx + 30, 104], radius=4, fill=GROUP_COLORS[key], outline=LINE); d.text((lx + 38, 78), label, font=F(19), fill=INK); lx += 48 + d.textlength(label, font=F(19)) + 24
    cols = {}
    for t in tables:
        cols[t] = ['id PK'] if t not in ('account_policy', 'managed_role', 'session_activity', 'login_attempt') else [{'account_policy': 'user_id PK', 'managed_role': 'role_id PK', 'session_activity': 'session_id PK', 'login_attempt': 'username PK'}[t]]
    for a, c, b in fks:
        entry = f'{c} → {b}'
        if a in ('account_policy', 'managed_role') and c in ('user_id', 'role_id'): cols[a][0] = f'{c} PK → {b}'
        else: cols[a].append(entry)
    boxes = {}
    for t, (c, r, g) in layout.items():
        x = 40 + c * cw; y = 140 + r * rh; h = 46 + 28 * len(cols[t]) + 10
        boxes[t] = (x, y, x + bw, y + h)
    o = d
    for a, c, b in fks:
        ax0, ay0, ax1, ay1 = boxes[a]; bx0, by0, bx1, by1 = boxes[b]
        if ax0 == bx0: p, q = ((ax1, (ay0 + ay1) / 2), (bx1, (by0 + by1) / 2)); mid = ax1 + 40 + 6 * (fks.index((a, c, b)) % 5)
        elif ax0 < bx0: p, q = (ax1, (ay0 + ay1) / 2), (bx0, (by0 + by1) / 2); mid = None
        else: p, q = (ax0, (ay0 + ay1) / 2), (bx1, (by0 + by1) / 2); mid = None
        pts = [p, (mid, p[1]), (mid, q[1]), q] if mid else [p, ((p[0] + q[0]) / 2, p[1]), ((p[0] + q[0]) / 2, q[1]), q]
        o.line(pts, fill=(140, 152, 166), width=2)
        o.ellipse([q[0] - 5, q[1] - 5, q[0] + 5, q[1] + 5], fill=(110, 122, 136))
        crow = -1 if pts[1][0] < p[0] else 1
        o.line([(p[0], p[1] - 8), (p[0] + 12 * crow, p[1]), (p[0], p[1] + 8)], fill=(110, 122, 136), width=2)
    for t, (c, r, g) in layout.items():
        x, y, x1, y1 = boxes[t]
        d.rectangle([x, y, x1, y1], fill=GROUP_COLORS[g], outline=INK, width=2); d.line([(x, y + 40), (x1, y + 40)], fill=INK, width=2)
        center(d, (x + bw / 2, y + 7), t, F(21, True))
        for i, col in enumerate(cols[t]): d.text((x + 14, y + 48 + i * 28), col, font=F(17), fill=INK)
    img.save(OUT / 'er-full.png'); print('er-full.png', len(tables), 'tables', len(fks), 'fks')

# ---------- UI navigation ----------
def navigation():
    W, H = 2300, 1320; img = Image.new('RGB', (W, H), BG); d = ImageDraw.Draw(img)
    d.text((40, 24), '页面跳转与角色可见性', font=F(36, True), fill=INK)
    d.text((40, 76), '标记：S 超级管理员 · D 部门管理员（仅绑定部门数据） · U 普通用户。侧栏只渲染当前身份可见的页面，后端对每个请求独立鉴权。', font=F(20), fill=MUTED)
    def node(x, y, w, h, title, lines=(), fill=(246, 248, 250)):
        d.rounded_rectangle([x, y, x + w, y + h], radius=10, fill=fill, outline=INK, width=2); center(d, (x + w / 2, y + 10), title, F(22, True))
        for i, line in enumerate(lines): d.text((x + 16, y + 48 + i * 30), line, font=F(19), fill=INK)
        return (x, y, x + w, y + h)
    def arrow(a, b, label='', dashed=False):
        import math
        n = max(int(math.dist(a, b) // 12), 1)
        for i in range(0, n, 2 if dashed else 1):
            t0, t1 = i / n, min((i + 1) / n, 1)
            d.line([(a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0), (a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1)], fill=LINE, width=3)
        ang = math.atan2(b[1] - a[1], b[0] - a[0])
        d.polygon([b, (b[0] - 16 * math.cos(ang - .4), b[1] - 16 * math.sin(ang - .4)), (b[0] - 16 * math.cos(ang + .4), b[1] - 16 * math.sin(ang + .4))], fill=LINE)
        if label: center(d, ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2 - 30), label, F(18), MUTED)
    login = node(40, 520, 230, 120, '登录页', ['账号或手机号 + 密码'], (234, 240, 249))
    change = node(40, 820, 230, 120, '个人中心（改密）', ['首次登录或管理员重置后'], (250, 243, 229))
    frame = node(380, 520, 250, 120, '主框架', ['顶栏身份 · 侧栏导航'], (232, 242, 236))
    arrow((270, 580), (380, 580), '登录成功')
    arrow((155, 640), (155, 820), '须先改密')
    arrow((270, 880), (380, 620), '改密后重新登录', dashed=True)
    groups = [
        ('开始', ['工作台 S D U', '应用门户 S D U → OA/HR 客户端（OIDC 免登）'], 150),
        ('基础管理', ['用户管理 S D', '外部与临时人员 S D', '初始密码 S D', '角色管理 S D', '权限管理 S', '应用管理 S'], 290),
        ('组织数据', ['部门管理 S', '岗位管理 S', '三表导入 S', '功能权限目录 S', '字典管理 S'], 520),
        ('运行记录', ['在线会话 S D U', '操作日志 S D', '部门会话 S D', '登录日志 S D', '权限申请 S D U', '审批记录 S D', '同步记录 S'], 730),
        ('本人', ['个人中心 S D U', '我的权限 S D U', '报销权限演示 S D U'], 1000),
    ]
    y = 140
    for title, lines, _ in groups:
        h = 52 + 30 * len(lines); box = node(760, y, 620, h, title, lines)
        arrow((630, 580), (760, y + h / 2)); y += h + 24
    states = node(1520, 380, 330, 210, '状态页', ['403 访问受限：保留登录', '404 页面不存在', '401 登录失效：回到登录页'], (251, 238, 238))
    arrow((1380, 470), (1520, 470))
    center(d, (1685, 600), '无权限或路径不存在时进入', F(18), MUTED)
    d.line([(1685, 590), (1685, 1250), (155, 1250), (155, 940)], fill=LINE, width=3)
    d.polygon([(155, 940), (147, 956), (163, 956)], fill=LINE)
    center(d, (900, 1260), '401：令牌失效且 30 分钟内无操作（刷新失败）时清除会话，回到登录页', F(18), MUTED)
    clients = node(1520, 140, 330, 150, 'OA / HR 演示客户端', ['授权码 + PKCE', '已有会话无需再输密码'], (243, 237, 248))
    arrow((1380, 200), (1520, 210), '打开客户端')
    img.save(OUT / 'ui-navigation.png'); print('ui-navigation.png')

if __name__ == '__main__':
    er(); navigation()
