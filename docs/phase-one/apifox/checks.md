# Apifox 检查场景

导入 openapi.json 后，在测试场景中从接口添加步骤。baseUrl 为本地 18081，管理员口令只保存在本地私密环境。每轮使用唯一的 demo_ 前缀，保存本轮创建的用户、角色及权限编号。不要导入真实员工资料和运行密钥。

| 场景 / Spec | 步骤 | 断言 |
| --- | --- | --- |
| AUTH-01 登录 | POST /api/auth/login | 200；accessToken 非空；保存 adminToken |
| SCOPE-01 普通用户越权 | 普通用户 JWT 请求 GET /api/users | 403；保持登录 |
| RBAC-01 权限并集 | 创建两个角色，共同拥有 expense:list；赋给演示用户；用户访问报销 | 200 |
| RBAC-01 回收部分 | 管理员回收一个角色；使用同一个 userToken 访问报销 | 200 |
| RBAC-01 回收全部 | 回收第二角色；继续使用原 userToken | 403 |
| SESSION-01 注销 | 用户 POST /api/auth/logout，再使用原 userToken 请求报销 | 204，然后 401 |
| IMPORT-01 导入 | 三个虚构 XLSX 预览、确认提交、相同文件再提交 | errors 为空；重复提交 reused=true |
| HR-01 幂等 | 同一签名事件重发；同编号改变内容重新签名 | 首次 reused=false，重发 true，冲突 409 |
| OA-01 审批 | 用户创建申请；签名 APPROVED 回调 | 申请由 PENDING 变为 APPROVED；授予申请内角色 |

登录接口后置脚本：
```javascript
pm.test('登录成功', () => pm.response.to.have.status(200));
const data = pm.response.json();
pm.test('返回访问令牌', () => pm.expect(data.accessToken).to.be.a('string').and.not.empty);
pm.environment.set('adminToken', data.accessToken);
```

拒绝越权步骤后置脚本：
```javascript
pm.test('普通用户不能管理', () => pm.response.to.have.status(403));
```

检查重点：撤权检查复用同一个 userToken，不能重新登录掩盖缓存问题。单独保存 adminToken 和 userToken，不能混用。OA/HR 的原始 JSON、请求编号和时间戳参与签名，签名后不再格式化 JSON。

Apifox 运行完成后导出报告，并记录项目地址、运行时间、环境和通过数。运行证据以实际报告为准。

官方参考：https://apifox.com/help/api-manage/import-api/intro/ ；https://docs.apifox.com/scripts
