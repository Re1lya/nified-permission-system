"""Draw the use case, domain model and sequence diagrams used by the requirements document.
Status colours follow docs/phase-one/requirements.md section 12; update both together.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/phase-one/images';OUT.mkdir(parents=True,exist_ok=True)
FONT='C:/Windows/Fonts/msyh.ttc';BOLD='C:/Windows/Fonts/msyhbd.ttc'
F=lambda size,bold=False:ImageFont.truetype(BOLD if bold else FONT,size)
INK=(32,41,52);LINE=(92,104,118);MUTED=(110,120,132);BG=(255,255,255)
DONE=(222,241,229);PART=(253,240,205);TODO=(238,240,243)
STATUS={'done':(DONE,'已实现'),'part':(PART,'部分实现'),'todo':(TODO,'未实现')}

def text_center(d,xy,s,font,fill=INK):
    w=d.textlength(s,font=font);d.text((xy[0]-w/2,xy[1]),s,font=font,fill=fill)

def arrow(d,a,b,fill=LINE,width=2,dash=False,head='open'):
    import math
    if dash:
        n=int(math.dist(a,b)//14)
        for i in range(0,n,2):
            t0,t1=i/n,min((i+1)/n,1)
            d.line([(a[0]+(b[0]-a[0])*t0,a[1]+(b[1]-a[1])*t0),(a[0]+(b[0]-a[0])*t1,a[1]+(b[1]-a[1])*t1)],fill=fill,width=width)
    else:d.line([a,b],fill=fill,width=width)
    if head:
        ang=math.atan2(b[1]-a[1],b[0]-a[0]);L=16
        p1=(b[0]-L*math.cos(ang-0.4),b[1]-L*math.sin(ang-0.4));p2=(b[0]-L*math.cos(ang+0.4),b[1]-L*math.sin(ang+0.4))
        if head=='filled':d.polygon([b,p1,p2],fill=fill)
        elif head=='hollow':d.polygon([b,p1,p2],fill=BG,outline=fill)
        else:d.line([p1,b,p2],fill=fill,width=width)

def legend(d,x,y):
    f=F(20)
    for key in ['done','part','todo']:
        color,label=STATUS[key]
        d.rounded_rectangle([x,y,x+44,y+26],radius=13,fill=color,outline=LINE,width=2)
        d.text((x+54,y-1),label,font=f,fill=INK);x+=54+d.textlength(label,font=f)+36

def actor(d,cx,top,name):
    d.ellipse([cx-16,top,cx+16,top+32],outline=INK,width=3)
    d.line([(cx,top+32),(cx,top+80)],fill=INK,width=3);d.line([(cx-30,top+48),(cx+30,top+48)],fill=INK,width=3)
    d.line([(cx,top+80),(cx-26,top+118)],fill=INK,width=3);d.line([(cx,top+80),(cx+26,top+118)],fill=INK,width=3)
    text_center(d,(cx,top+126),name,F(22,True))
    return (cx,top+60)

def usecase_diagram(name,title,left,right,cases,links,includes=()):
    """cases: [(id,label,status)]; links: [(actor,case id)]; includes: [(from id,to id,stereotype)]."""
    row=78;top=150;W=1700;H=top+len(cases)*row+90
    img=Image.new('RGB',(W,H),BG);d=ImageDraw.Draw(img)
    d.text((40,28),title,font=F(34,True),fill=INK);legend(d,40,86)
    bx0,bx1=380,1320
    d.rectangle([bx0,top-20,bx1,H-40],outline=INK,width=2);d.text((bx0+16,top-12),'统一认证与权限平台',font=F(20),fill=MUTED)
    centers={}
    for i,(cid,label,status) in enumerate(cases):
        cy=top+40+i*row;cx=(bx0+bx1)//2;centers[cid]=(cx,cy)
        d.ellipse([cx-300,cy-29,cx+300,cy+29],fill=STATUS[status][0],outline=LINE,width=2)
        text_center(d,(cx,cy-15),f'{cid}  {label}' if cid[0].isupper() else label,F(22))
    def place(actors,x):
        pos={};span=(H-top-60)/(len(actors)+1)
        for i,a in enumerate(actors):pos[a]=actor(d,x,int(top+span*(i+1)-70),a)
        return pos
    pos={**place(left,170),**place(right,1530)}
    for a,cid in links:
        ax,ay=pos[a];cx,cy=centers[cid];ex=cx-300 if ax<cx else cx+300
        d.line([(ax+(36 if ax<cx else -36),ay),(ex,cy)],fill=LINE,width=2)
    for a,b,st in includes:
        (x0,y0),(x1,y1)=centers[a],centers[b];x=x0+320
        arrow(d,(x-20,y0),(x+40,y0),dash=True,head=None);arrow(d,(x+40,y0),(x+40,y1),dash=True,head=None);arrow(d,(x+40,y1),(x0+300,y1),dash=True)
        d.text((x+50,(y0+y1)/2-12),f'«{st}»',font=F(18),fill=MUTED)
    img.save(OUT/name);print(name)

usecase_diagram('req-usecase-auth.png','用例图 1  认证、单点登录与自助',['普通用户'],['接入应用'],[
    ('UC-A01','账号密码登录','done'),('UC-A02','手机号密码登录','done'),('UC-A03','SSO 免登','part'),('UC-A04','登出','done'),
    ('UC-A10','本人修改密码','done'),('S8','查看本人权限 / 应用门户','part'),('UC-E02','提交申请与查询审批记录','done'),
    ('UC-A06','令牌校验','done'),('UC-A07','令牌刷新','done'),('UC-A08','令牌撤销','done'),('UC-B07','鉴权决策','done'),('UC-B08','权限集合下发','done')],
    [('普通用户',c) for c in ['UC-A01','UC-A02','UC-A03','UC-A04','UC-A10','S8','UC-E02']]+[('接入应用',c) for c in ['UC-A03','UC-A06','UC-A07','UC-A08','UC-B07','UC-B08']])

usecase_diagram('req-usecase-admin.png','用例图 2  授权与平台管理',['部门管理员','超级管理员'],['接入应用'],[
    ('UC-B01','应用注册','done'),('UC-B02','应用接入配置','part'),('UC-B03','权限点注册与维护','done'),('UC-B04','角色管理','done'),
    ('UC-B05','用户-角色分配','done'),('UC-B06','角色-权限分配','done'),('UC-C05','外部人员自建与有效期','done'),('UC-A11','强制下线','done'),
    ('UC-F04','审计日志查询','part'),('UC-F05','权限复核','todo'),('扩展','三表导入预览与提交','done'),('扩展2','任命部门管理员与委派上限','done')],
    [('超级管理员',c) for c in ['UC-B01','UC-B02','UC-B03','UC-B04','UC-B05','UC-B06','UC-C05','UC-A11','UC-F04','UC-F05','扩展','扩展2']]
    +[('部门管理员',c) for c in ['UC-B05','UC-B06','UC-C05','UC-A11','UC-F04','UC-F05']]+[('接入应用','UC-B03')])

usecase_diagram('req-usecase-sync.png','用例图 3  身份同步、OA 集成与审计',['HR 系统','OA 系统'],['超级管理员','接入应用'],[
    ('UC-C01','HR 员工信息同步','part'),('UC-C04','账号自动创建','part'),('UC-C02','部门信息同步','part'),('UC-C03','岗位信息同步','part'),
    ('UC-C06','离职禁用','done'),('UC-C07','调岗权限回收与再分配','done'),('UC-C08','同步异常处理与对账','todo'),
    ('UC-E01','审批结果回调处理','done'),('UC-E03','申请单状态同步','part'),('UC-D02','用户与部门数据下发','done'),
    ('UC-F01','登录 / 操作 / 权限变更日志','done'),('UC-F06','日志归档','todo')],
    [('HR 系统',c) for c in ['UC-C01','UC-C02','UC-C03','UC-C06','UC-C07']]+[('OA 系统',c) for c in ['UC-E01','UC-E03']]
    +[('超级管理员',c) for c in ['UC-C08','UC-F06']]+[('接入应用','UC-D02')],includes=[('UC-C01','UC-C04','include')])

# ---------- domain model ----------
def domain():
    W,H=2220,1420;img=Image.new('RGB',(W,H),BG);d=ImageDraw.Draw(img)
    d.text((40,28),'领域模型（RBAC0，一期）',font=F(34,True),fill=INK)
    d.text((40,80),'只画核心概念；会话、审计、集成事件等记录表见数据库设计文档。',font=F(20),fill=MUTED)
    cols=[120,660,1200,1740];rows=[150,590,1030];BW=400
    classes={
        'dept':(0,0,'Department 部门',['name 名称','parent 上级部门','manager 负责人']),
        'post':(1,0,'Post 岗位',['name 岗位名称']),
        'role':(2,0,'Role 角色',['code 编码','name 名称','enabled 启用']),
        'rp':(3,0,'RolePermission 角色权限',['role','permission']),
        'emp':(0,1,'EmployeeProfile 员工档案',['company 分公司','phone 电话']),
        'user':(1,1,'User 用户 / 账号',['username 账号名','displayName 姓名','enabled 启用','administrator 超管标记']),
        'ur':(2,1,'UserRole 用户角色',['user','role']),
        'perm':(3,1,'Permission 权限点',['code 应用:模块:操作','name 名称','menuPath 资源路径','type 菜单/按钮/API']),
        'admin':(0,2,'AdminDepartment 管辖部门',['user 部门管理员','department']),
        'policy':(1,2,'AccountPolicy 账号策略',['personType 人员类型','expiresAt 有效期','mustChangePassword','version 权限版本号']),
        'session':(2,2,'LoginSession 会话',['issuedAt','expiresAt','lastSeen']),
        'app':(3,2,'Application 应用',['code','name','baseUrl','enabled']),
    }
    box={}
    for k,(c,r,name,attrs) in classes.items():
        x,y=cols[c],rows[r];h=58+len(attrs)*34+16;box[k]=(x,y,x+BW,y+h)
        d.rectangle([x,y,x+BW,y+h],fill=(246,248,250),outline=INK,width=2);d.line([(x,y+52),(x+BW,y+52)],fill=INK,width=2)
        text_center(d,(x+BW/2,y+12),name,F(23,True))
        for i,a in enumerate(attrs):
            planned=a.startswith('~');d.text((x+18,y+64+i*34),a.lstrip('~'),font=F(20),fill=MUTED if planned else INK)
            if planned:d.text((x+BW-84,y+64+i*34),'计划',font=F(18),fill=(176,110,20))
    def edge(a,b):
        ax0,ay0,ax1,ay1=box[a];bx0,by0,bx1,by1=box[b]
        if abs(ay0-by0)<5:return ((ax1,ay0+80),(bx0,by0+80)) if ax0<bx0 else ((ax0,ay0+80),(bx1,by0+80))
        if abs(ax0-bx0)<5:return ((ax0+BW/2,ay1),(bx0+BW/2,by0)) if ay0<by0 else ((ax0+BW/2,ay0),(bx0+BW/2,by1))
        return ((ax1,ay0+30),(bx0,by1-30)) if ax0<bx0 else ((ax0,ay0+30),(bx1,by1-30))
    def assoc(a,b,ma,mb,label=''):
        p,q=edge(a,b);d.line([p,q],fill=LINE,width=2);f=F(20)
        ux,uy=(q[0]-p[0]),(q[1]-p[1]);n=max((ux*ux+uy*uy)**.5,1);ux,uy=ux/n,uy/n
        d.text((p[0]+ux*22+(-uy)*20-6,p[1]+uy*22+ux*20-12),ma,font=f,fill=INK)
        d.text((q[0]-ux*48+(-uy)*20-6,q[1]-uy*48+ux*20-12),mb,font=f,fill=INK)
        if label:text_center(d,((p[0]+q[0])/2-(-uy)*26,(p[1]+q[1])/2-ux*26-14),label,F(18),MUTED)
    assoc('emp','dept','*','1','所属');assoc('emp','user','1','1');assoc('emp','post','*','1','任职')
    assoc('post','role','1','1','同名角色');assoc('user','ur','1','*');assoc('ur','role','*','1')
    assoc('role','rp','1','*');assoc('rp','perm','*','1');assoc('perm','app','*','1','归属')
    assoc('user','policy','1','1');assoc('session','user','*','1','登录');assoc('admin','user','*','1')
    x0,y0,x1,y1=box['admin'];dx0,dy0,dx1,dy1=box['dept']
    d.line([(x0,y0+100),(60,y0+100),(60,dy0+100),(dx0,dy0+100)],fill=LINE,width=2)
    d.text((96,y0+68),'*',font=F(20),fill=INK);d.text((96,dy0+106),'1',font=F(20),fill=INK);d.text((14,440),'管辖',font=F(18),fill=MUTED)
    d.line([(dx1-60,dy0),(dx1-60,dy0-36),(dx1+50,dy0-36),(dx1+50,dy0+40),(dx1,dy0+40)],fill=LINE,width=2)
    d.text((dx1+58,dy0-30),'0..1 上级',font=F(18),fill=MUTED);d.text((dx1-52,dy0-34),'*',font=F(20),fill=INK)
    img.save(OUT/'req-domain-model.png');print('req-domain-model.png')
domain()

# ---------- sequence diagrams ----------
def sequence(name,title,parts,msgs,notes=()):
    """msgs: (from,to,text,kind) kind: call | return | self. notes: (after message index, text)."""
    gap=380;W=120+gap*len(parts)+240;step=74;H=230+len(msgs)*step+len(notes)*60+60
    img=Image.new('RGB',(W,H),BG);d=ImageDraw.Draw(img)
    d.text((40,28),title,font=F(34,True),fill=INK)
    xs={p:120+gap*i+gap//2-60 for i,p in enumerate(parts)}
    for p,x in xs.items():
        w=d.textlength(p,font=F(22,True))+40;d.rectangle([x-w/2,100,x+w/2,150],fill=(246,248,250),outline=INK,width=2)
        text_center(d,(x,111),p,F(22,True));arrow(d,(x,150),(x,H-30),fill=(170,178,188),dash=True,head=None)
    y=210;note_after=dict(notes)
    for i,(a,b,text,kind) in enumerate(msgs):
        f=F(20)
        if kind=='self':
            x=xs[a];d.line([(x,y-10),(x+60,y-10),(x+60,y+22)],fill=LINE,width=2);arrow(d,(x+60,y+22),(x+4,y+22))
            label=f'{i+1}. {text}';tw=d.textlength(label,font=f);d.rectangle([x+66,y-12,x+78+tw,y+18],fill=BG);d.text((x+72,y-10),label,font=f,fill=INK)
        else:
            x0,x1=xs[a],xs[b];arrow(d,(x0,y+10),(x1,y+10),dash=kind=='return',head='filled' if kind=='call' else 'open')
            label=f'{i+1}. {text}';tw=d.textlength(label,font=f);mx=(x0+x1)/2;d.rectangle([mx-tw/2-6,y-24,mx+tw/2+6,y+4],fill=BG);text_center(d,(mx,y-22),label,f)
        y+=step
        if i in note_after:
            t=note_after[i];w=d.textlength(t,font=F(19))+30;x=120
            d.rectangle([x,y-24,x+w,y+16],fill=(255,248,220),outline=(200,170,90),width=2);d.text((x+15,y-20),t,font=F(19),fill=INK);y+=60
    img.save(OUT/name);print(name)

sequence('req-seq-onboard.png','时序图 1  入职（UC-C01 / UC-C04，BR-03 / BR-10）',['HR 系统','权限平台','部门管理员','新员工'],[
    ('HR 系统','权限平台','JOIN 签名事件','call'),('权限平台','权限平台','验签、幂等检查、建账号与员工档案','self'),
    ('权限平台','HR 系统','SUCCESS（初始无角色）','return'),('部门管理员','权限平台','重置初始密码（标记首次改密）','call'),
    ('新员工','权限平台','首次登录','call'),('权限平台','新员工','要求先修改初始密码','return'),('新员工','权限平台','修改密码','call'),
    ('部门管理员','权限平台','按岗位分配角色','call'),('权限平台','权限平台','权限版本号 +1，写审计','self')],
    notes=[(2,'偏离：账号名沿用 HR 工号，不按拼音生成；初始密码由管理员重置而非自动生成')])
sequence('req-seq-transfer.png','时序图 2  调岗（UC-C07，BR-12）',['HR 系统','权限平台','接入应用','员工'],[
    ('HR 系统','权限平台','TRANSFER：新部门、岗位、完整角色列表','call'),('权限平台','权限平台','锁定账号，校验岗位与角色','self'),
    ('权限平台','权限平台','同一事务：回收旧角色、更新档案、授予新角色','self'),('权限平台','权限平台','权限版本号 +1，写集成事件','self'),
    ('权限平台','HR 系统','SUCCESS','return'),('员工','接入应用','下一次请求','call'),('接入应用','权限平台','校验令牌与权限版本','call'),
    ('权限平台','接入应用','版本已变化，要求重新取令牌','return')],notes=[(4,'未实现：通知原部门 / 新部门管理员')])
sequence('req-seq-leave.png','时序图 3  离职（UC-C06，BR-19）',['HR 系统','权限平台','接入应用'],[
    ('HR 系统','权限平台','LEAVE 签名事件','call'),('权限平台','权限平台','停用账号、回收全部角色','self'),
    ('权限平台','权限平台','撤销全部会话，权限版本号 +1','self'),('权限平台','HR 系统','SUCCESS','return'),
    ('接入应用','权限平台','校验旧令牌','call'),('权限平台','接入应用','401 会话已失效','return')])
sequence('req-seq-approval.png','时序图 4  权限申请与 OA 审批（UC-E01 / UC-E02，BR-20）',['申请人','权限平台','OA 系统'],[
    ('申请人','权限平台','提交申请（岗位角色）','call'),('权限平台','申请人','PENDING 申请编号','return'),
    ('申请人','OA 系统','在 OA 发起审批（携带申请编号）','call'),('OA 系统','OA 系统','部门管理员审批','self'),
    ('OA 系统','权限平台','签名回调 APPROVED / REJECTED','call'),('权限平台','权限平台','验签、幂等、锁定申请','self'),
    ('权限平台','权限平台','批准则授予角色，权限版本号 +1','self'),('权限平台','OA 系统','处理结果','return')],
    notes=[(1,'偏离：需求为申请在 OA 发起；一期先在平台登记申请，以便回调只能处理已存在的申请')])
sequence('req-seq-sso.png','时序图 5  SSO 免登（UC-A03，BR-09）',['用户','统一门户','接入应用','认证中心'],[
    ('用户','统一门户','账号密码登录','call'),('统一门户','认证中心','建立单点登录会话','call'),('用户','接入应用','打开应用','call'),
    ('接入应用','认证中心','重定向 /oauth2/authorize（PKCE）','call'),('认证中心','认证中心','已有会话，无需再输密码','self'),
    ('认证中心','接入应用','一次性授权码','return'),('接入应用','认证中心','/oauth2/token 换取令牌','call'),
    ('认证中心','接入应用','ID / Access Token（含 permission_version）','return'),('接入应用','认证中心','/api/oidc/permissions/check','call'),
    ('认证中心','接入应用','允许 / 拒绝','return')],notes=[(9,'未实现：refresh_token；一期客户端与密钥保存在内存')])
