'use strict';
// Tokens live only in this tab's memory. Reloading requires login; never put credentials in a URL.
let token = '', me = null, current = 'users', revision = 0;
const $ = s => document.querySelector(s);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const sections = {
  users: {title:'用户管理', hint:'先创建账号，再设置密码和分配角色。停用账号会撤销它的全部登录会话。', columns:['用户名','显示名称','身份','状态'], cells:r=>[r.username,r.displayName,r.administrator?'管理员':'普通用户',badge(r.enabled)]},
  roles: {title:'角色管理', hint:'角色是一组权限。多个角色的权限取并集，不包含继承或互斥规则。', columns:['编码','角色名称','说明','状态'], cells:r=>[r.code,r.name,r.description,badge(r.enabled)]},
  systems: {title:'业务系统', hint:'登记权限所属系统。停用系统后，其权限不再参与有效权限计算。', columns:['编码','系统名称','访问地址','状态'], cells:r=>[r.code,r.name,r.baseUrl,badge(r.enabled)]},
  permissions: {title:'权限管理', hint:'权限编码全局唯一。报销示例使用 expense:list；菜单路径仅作为设计信息展示。', columns:['权限编码','权限名称','所属系统','菜单路径'], cells:r=>[r.code,r.name,r.systemCode,r.menuPath]}
};
function badge(enabled){return {html:`<span class="badge ${enabled?'':'off'}">${enabled?'启用':'停用'}</span>`};}
function notice(message, kind='success'){const n=$('#notice');n.textContent=message;n.dataset.kind=kind;n.hidden=false;n.scrollIntoView({block:'nearest'});}
function signedOut(){token='';me=null;revision++;$('#identity').replaceChildren();$('#workspace').hidden=true;$('#login').hidden=false;$('#login-form').reset();}
async function api(path, method='GET', body, options={}){
  let response;
  try{response=await fetch(path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} :{}),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,cache:'no-store'});}
  catch{throw new Error('连接失败，请确认本地服务已启动，然后重试。');}
  const data=response.status===204?null:await response.json().catch(()=>null);
  if(!response.ok){
    if(response.status===401&&!options.login){signedOut();notice('401 · 登录状态已失效，请重新登录。','error');}
    const error=new Error(response.status===403?'403 · 无权执行此操作。请联系管理员分配权限。':data?.message||`请求失败 (${response.status})`);
    error.status=response.status;throw error;
  }
  return data;
}
async function busy(button, action){button.disabled=true;try{await action();}catch(error){notice(error.message,'error');}finally{button.disabled=false;}}
$('#login-form').addEventListener('submit',event=>{event.preventDefault();const form=event.currentTarget;busy(form.querySelector('button'),async()=>{
  const fields=Object.fromEntries(new FormData(form));const login=await api('/api/auth/login','POST',fields,{login:true});token=login.accessToken;
  me=await api('/api/auth/me');form.reset();$('#login').hidden=true;$('#workspace').hidden=false;
  $('#identity').innerHTML=`<span>${esc(me.displayName)} · ${me.administrator?'管理员':'普通用户'}</span> <button id="logout">注销</button>`;
  $('#logout').onclick=event=>busy(event.currentTarget,async()=>{await api('/api/auth/logout','POST');signedOut();notice('已注销，当前 JWT 已在服务端撤销。');});
  $('nav').innerHTML=(me.administrator?Object.entries(sections).map(([key,value])=>`<button data-page="${key}">${value.title}</button>`).join(''):'')+'<button data-page="mine">我的有效权限</button><button data-page="demo">报销权限演示</button>';
  $('nav').onclick=event=>{const page=event.target.closest('[data-page]');if(page)show(page.dataset.page);};
  notice('登录成功。');await show(me.administrator?'users':'mine');
});});
function table(columns,rows){return `<div class="table-wrap"><table><thead><tr>${columns.map(c=>`<th scope="col">${esc(c)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(row=>`<tr>${row.map(v=>`<td>${v?.html??esc(v)}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${columns.length}" class="empty">暂无记录，请使用上方“新建”开始配置。</td></tr>`}</tbody></table></div>`;}
async function show(page){
  current=page;const version=++revision;
  document.querySelectorAll('nav button').forEach(b=>b.setAttribute('aria-current',b.dataset.page===page?'page':'false'));
  $('#main').innerHTML='<p role="status">正在加载，请稍候…</p>';
  try{
    if(page==='demo'){renderDemo();return true;}
    if(page==='mine'){
      const self=await api('/api/auth/me');if(version!==revision)return;
      $('#main').innerHTML='<div class="toolbar"><div><h1>我的有效权限</h1><p class="muted">每次刷新都从后端重新计算，登录时的权限快照不作为授权依据。</p></div><button id="refresh">刷新</button></div>'+permissionList(self.permissions);
      $('#refresh').onclick=()=>show('mine');return true;
    }
    const config=sections[page], rows=await api(`/api/${page}`);if(version!==revision)return;
    $('#main').innerHTML=`<div class="toolbar"><div><h1>${config.title}</h1><p class="muted">${config.hint}</p></div><div class="actions"><button id="refresh">刷新</button><button id="create" class="primary">新建</button></div></div>`+
      table([...config.columns,'操作'],rows.map(r=>[...config.cells(r),{html:`<div class="actions"><button data-edit="${r.id}">编辑</button>${page==='users'?`<button data-password="${r.id}">设置密码</button><button data-assign="${r.id}">角色与权限</button>`:page==='roles'?`<button data-assign="${r.id}">配置权限</button>`:''}</div>`}]))+'<section id="editor" class="editor" hidden></section>';
    $('#refresh').onclick=()=>show(page);$('#create').onclick=()=>edit(page);
    $('#main').onclick=event=>{const b=event.target.closest('button');if(!b)return;const row=rows.find(r=>String(r.id)===(b.dataset.edit||b.dataset.password||b.dataset.assign));
      if(b.dataset.edit)edit(page,row);if(b.dataset.password)password(row);if(b.dataset.assign)busy(b,()=>assign(page,row));};
    return true;
  }catch(error){if(version===revision)$('#main').innerHTML='<h1>暂时无法加载</h1><p>请检查提示后重试。</p><button id="retry">重新加载</button>';if($('#retry'))$('#retry').onclick=()=>show(page);notice(error.message,'error');return false;}
}
function field(name,label,value='',max=128,required=true,type='text'){return `<label>${label}<input name="${name}" value="${esc(value)}" maxlength="${max}" ${required?'required':''} type="${type}" ${type==='password'?'autocomplete="new-password"':''}></label>`;}
function openEditor(html){$('#editor').innerHTML=html;$('#editor').hidden=false;$('#editor').scrollIntoView({behavior:'instant',block:'nearest'});$('#editor').querySelector('input,select,button')?.focus();}
async function edit(page,row){
  try{
    const version=revision;let fields='';
    if(page==='users')fields=(row?'':field('username','用户名','',64))+field('displayName','显示名称',row?.displayName);
    else fields=(row?'':field('code',page==='permissions'?'权限编码，例如 expense:list':'编码','',page==='permissions'?128:64))+field('name','名称',row?.name);
    if(page==='roles')fields+=field('description','说明（选填）',row?.description,255,false);
    if(page==='systems')fields+=field('baseUrl','访问地址（选填）',row?.baseUrl,255,false);
    if(page==='permissions'){
      if(!row){const systems=await api('/api/systems');if(version!==revision)return;
        if(!systems.length){notice('请先在“业务系统”中新建一个系统。','error');return;}
        fields+=`<label>所属系统<select name="systemId" required>${systems.map(s=>`<option value="${s.id}">${esc(s.name)} (${esc(s.code)})</option>`).join('')}</select></label>`;}
      fields+=field('menuPath','菜单路径',row?.menuPath,255);
    }
    if(row&&page!=='permissions')fields+=`<label class="check"><input type="checkbox" name="enabled" ${row.enabled?'checked':''} ${row.administrator?'disabled':''}>启用${row.administrator?'（保护管理员，不能停用）':''}</label>`;
    openEditor(`<h2>${row?'编辑':'新建'} · ${sections[page].title}</h2><form id="edit-form"><div class="fields">${fields}</div><div class="actions"><button class="primary">保存</button><button type="button" id="cancel">取消</button></div></form>`);
    $('#cancel').onclick=()=>{$('#editor').hidden=true;};
    $('#edit-form').onsubmit=event=>{event.preventDefault();const form=event.currentTarget;busy(form.querySelector('button'),async()=>{
      const body=Object.fromEntries(new FormData(form));for(const key in body)body[key]=body[key].trim();
      if(row&&page!=='permissions')body.enabled=row.administrator||form.elements.enabled.checked;
      if(body.systemId)body.systemId=Number(body.systemId);
      await api(`/api/${page}${row?'/'+row.id:''}`,row?'PUT':'POST',body);
      if(!await show(page)){notice('数据已保存，但列表刷新失败，请重试加载。','error');return;}
      notice(page==='users'&&!row?'用户已创建。请设置密码后再登录。':'保存成功，列表已刷新。');
    });};
  }catch(error){notice(error.message,'error');}
}
function password(row){
  openEditor(`<h2>设置密码 · ${esc(row.displayName)}</h2><p class="muted">保存后，该用户全部会话失效，需要重新登录。密码至少 8 个字符，UTF-8 编码不超过 72 字节。</p><form id="password-form"><div class="fields">${field('password','新密码','',72,true,'password')}${field('confirm','确认新密码','',72,true,'password')}</div><button class="primary">保存密码</button></form>`);
  $('#password-form').onsubmit=event=>{event.preventDefault();const form=event.currentTarget;busy(form.querySelector('button'),async()=>{
    const value=form.elements.password.value;
    if(value.length<8||new TextEncoder().encode(value).length>72)throw new Error('密码至少 8 个字符，且 UTF-8 编码不能超过 72 字节。');
    if(value!==form.elements.confirm.value)throw new Error('两次密码输入不一致。');
    await api(`/api/users/${row.id}/password`,'PUT',{password:value});form.reset();
    if(row.id===me.userId){signedOut();notice('密码已更新，请使用新密码登录。');}else{if(await show('users'))notice('密码已更新，该用户原有会话已撤销。');}
  });};
}
function permissionList(items){return items.length?`<ul class="permissions">${items.map(p=>`<li><strong>${esc(p.code)}</strong> · ${esc(p.name)} <small>${esc(p.systemCode)}</small></li>`).join('')}</ul>`:'<p class="muted">当前没有有效权限。请检查角色分配、角色启用状态和业务系统启用状态。</p>';}
async function assign(page,row){
  const version=revision, child=page==='users'?'roles':'permissions';
  const [all,assigned]=await Promise.all([api(`/api/${child}`),api(`/api/${page}/${row.id}/${child}`)]);
  let effective=page==='users'?await api(`/api/users/${row.id}/permissions`):null;if(version!==revision)return;
  const ids=new Set(assigned.map(r=>r.id));
  openEditor(`<h2>${page==='users'?'角色分配':'权限分配'} · ${esc(row.displayName||row.name)}</h2><p class="muted">每次操作立即保存。回收一条关系后，其他角色仍可能提供相同权限。</p><div class="check-list">${all.length?all.map(item=>`<label class="check"><input type="checkbox" data-link="${item.id}" ${ids.has(item.id)?'checked':''}><span>${esc(item.name)} · ${esc(item.code)}${item.enabled===false?'（已停用）':''}</span></label>`).join(''):'<p>暂无可分配记录，请先创建'+(child==='roles'?'角色':'权限')+'。</p>'}</div>${effective?'<h3>当前有效权限</h3>'+permissionList(effective):''}`);
  $('#editor').onchange=async event=>{const input=event.target;if(!input.dataset.link)return;$('#editor').querySelectorAll('input').forEach(i=>i.disabled=true);
    let saved=false;
    try{await api(`/api/${page}/${row.id}/${child}/${input.dataset.link}`,input.checked?'PUT':'DELETE');saved=true;await assign(page,row);notice('授权关系已保存，有效权限已重新查询。');}
    catch(error){if(!saved)input.checked=!input.checked;notice(saved?'授权已保存，但刷新失败，请重新打开配置区。':error.message,'error');}finally{if(!saved)$('#editor')?.querySelectorAll('input').forEach(i=>i.disabled=false);}};
}
function renderDemo(){
  $('#main').innerHTML='<h1>报销权限演示</h1><p class="muted">使用当前登录身份真实请求后端。需要 <strong>expense:list</strong> 权限；管理员身份不自动获得业务权限。</p><p class="badge">固定演示数据，不连接真实报销系统</p><div class="actions"><button id="request-expenses" class="primary">请求报销列表</button><button id="logout-probe">注销并用旧 JWT 验证 401</button></div><section id="demo-result" aria-live="polite"><p class="muted">尚未请求。可让管理员在另一浏览器窗口回收角色后，再次点击请求。</p></section>';
  $('#request-expenses').onclick=event=>busy(event.currentTarget,async()=>{
    $('#demo-result').textContent='正在请求…';try{const data=await api('/api/demo/expenses');$('#demo-result').innerHTML='<p class="result-code">200 · 访问成功</p>'+table(['演示编号','演示报销项目','演示金额（元）'],data.items.map(r=>[r.id,r.title,r.amount]));notice('后端已允许本次访问。');}
    catch(error){if($('#demo-result'))$('#demo-result').innerHTML=`<p class="result-code denied">${error.status||'网络错误'} · ${error.status===403?'没有报销列表权限':'请求失败'}</p><p>这是后端返回的结果。403 不会注销当前会话。</p>`;throw error;}
  });
  $('#logout-probe').onclick=event=>busy(event.currentTarget,async()=>{
    const oldToken=token;await api('/api/auth/logout','POST');signedOut();
    try{const response=await fetch('/api/demo/expenses',{headers:{Authorization:`Bearer ${oldToken}`},cache:'no-store'});notice(response.status===401?'401 · 注销成功。使用旧 JWT 再次访问已被后端拒绝，请重新登录。':`注销完成，但旧 JWT 验证返回 ${response.status}，请检查服务。`,response.status===401?'success':'error');}
    catch{notice('已注销，但旧 JWT 验证遇到网络错误。','error');}
  });
}
