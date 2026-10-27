package com.cdwy.permission.service;

import cn.edu.xmu.clonefactory.util.CloneFactory;
import com.cdwy.permission.dto.CreatePermissionRequest;
import com.cdwy.permission.dto.CreateRoleRequest;
import com.cdwy.permission.dto.CreateSystemRequest;
import com.cdwy.permission.dto.CreateUserRequest;
import com.cdwy.permission.dto.LoginRequest;
import com.cdwy.permission.dto.LoginResponse;
import com.cdwy.permission.dto.PermissionResponse;
import com.cdwy.permission.dto.RoleResponse;
import com.cdwy.permission.dto.SetPasswordRequest;
import com.cdwy.permission.dto.SystemResponse;
import com.cdwy.permission.dto.UpdatePermissionRequest;
import com.cdwy.permission.dto.UpdateRoleRequest;
import com.cdwy.permission.dto.UpdateSystemRequest;
import com.cdwy.permission.dto.UpdateUserRequest;
import com.cdwy.permission.dto.UserResponse;
import com.cdwy.permission.entity.BusinessSystem;
import com.cdwy.permission.entity.Permission;
import com.cdwy.permission.entity.Role;
import com.cdwy.permission.entity.RolePermission;
import com.cdwy.permission.entity.UserAccount;
import com.cdwy.permission.entity.UserRole;
import com.cdwy.permission.exception.AuthenticationFailedException;
import com.cdwy.permission.exception.ConflictException;
import com.cdwy.permission.exception.ForbiddenException;
import com.cdwy.permission.exception.ResourceNotFoundException;
import com.cdwy.permission.repository.BusinessSystemRepository;
import com.cdwy.permission.repository.PermissionRepository;
import com.cdwy.permission.repository.RolePermissionRepository;
import com.cdwy.permission.repository.RoleRepository;
import com.cdwy.permission.repository.UserAccountRepository;
import com.cdwy.permission.repository.UserRoleRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核心业务用例入口：账号生命周期、用户—角色、角色—权限关系以及有效权限计算。
 * 类级 readOnly=true 作为查询默认值，写方法用 @Transactional 覆盖；
 * 通过 Spring 代理进入的方法才建立事务，同类内部调用不会额外开启新事务。
 * Repository 负责实体存取，PlatformStore 处理平台扩展表；同一事务可包含两种访问方式。
 */
@Service
@Transactional(readOnly = true)
public class RbacService {

    private final UserAccountRepository userRepository;
    private final RoleRepository roleRepository;
    private final BusinessSystemRepository systemRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final com.cdwy.permission.platform.PlatformStore platform;

    public RbacService(
            UserAccountRepository userRepository,
            RoleRepository roleRepository,
            BusinessSystemRepository systemRepository,
            PermissionRepository permissionRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService, com.cdwy.permission.platform.PlatformStore platform) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.systemRepository = systemRepository;
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.platform = platform;
    }

    // 创建请求只携带用户名、姓名；管理员身份不能由普通账号创建请求任意传入。
    // exists 查询便于报错，最终仍由 sys_account.username 的唯一约束防止并发重复。
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("用户名已存在: " + request.username());
        }
        UserAccount user = CloneFactory.copy(new UserAccount(request.username(), request.displayName()), request);
        return toUserResponse(userRepository.save(user));
    }

    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream().filter(u -> platform.count("select count(*) from account_policy where user_id=? and deleted=true",u.getId())==0).map(this::toUserResponse).toList();
    }

    @Transactional
    public UserResponse updateUser(Long userId, UpdateUserRequest request) {
        UserAccount user = getUserForUpdate(userId);
        if (user.isAdministrator() && !request.enabled()) {
            throw new ConflictException("首次管理员不能停用");
        }
        CloneFactory.copyNotNull(user, request);
        if (!user.isEnabled()) {
            jwtTokenService.revokeAll(userId);
        }
        return toUserResponse(userRepository.save(user));
    }

    // 这里执行逻辑删除：撤销会话与角色、禁用账号并标记 deleted，保留历史对象供审计引用。
    // 这些业务变更在同一事务中完成，不能只删除用户表而留下仍可使用的会话。
    @Transactional
    public void deleteUser(Long userId) {
        if (getUserForUpdate(userId).isAdministrator()) {
            throw new ConflictException("首次管理员不能删除");
        }
        jwtTokenService.revokeAll(userId);
        userRoleRepository.deleteAllByUser_Id(userId);
        var user=getUserForUpdate(userId);
        user.setEnabled(false);user.setUsername("deleted_"+userId+"_"+java.util.UUID.randomUUID().toString().replace("-",""));
        user.setPasswordHash(null);platform.ensurePolicy(userId);
        platform.db().update("update account_policy set deleted=true where user_id=?",userId);
        platform.db().update("delete from admin_department where user_id=?",userId);
        platform.changed(userId);userRepository.save(user);
    }

    @Transactional
    public void setPassword(Long userId, SetPasswordRequest request) {
        UserAccount user = getUserForUpdate(userId);
        PasswordPolicy.validate(request.password());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        jwtTokenService.revokeAll(userId);
    }

    // 登录是写用例：除了校验密码，还会记录尝试结果、签发令牌并保存会话。
    // 用户名不存在时仅接受能唯一定位账号的手机号，避免重号登录到错误身份。
    @Transactional
    public LoginResponse login(LoginRequest request) {
        if(request.username()==null||request.username().isBlank()||request.username().length()>64||request.password()==null||request.password().length()>256)throw new AuthenticationFailedException();
        String username=request.username();
        if(!userRepository.existsByUsername(username)) {
            var candidates=platform.rows("select ac.username from employee_profile e join sys_user u on u.id=e.user_id join sys_account ac on ac.user_id=u.id where e.phone=?",username);
            if(candidates.size()==1) username=(String)candidates.getFirst().get("username");
        }
        // Count failures against the resolved account so username and phone attempts share one lockout.
        platform.notLocked(username);
        UserAccount user = userRepository.findByUsernameForUpdate(username)
                .filter(UserAccount::isEnabled)
                .filter(account -> account.getPasswordHash() != null)
                .filter(account -> passwordEncoder.matches(request.password(), account.getPasswordHash()))
                .orElse(null);
        if(user==null){platform.loginResult(username,false);platform.audit(null,"LOGIN",request.username(),"FAILED");throw new AuthenticationFailedException();}
        platform.validAccount(user.getId());
        platform.ensurePolicy(user.getId());
        platform.loginResult(username,true);
        platform.audit(user.getId(),"LOGIN",user.getUsername(),"SUCCESS",user.getId());
        String accessToken = jwtTokenService.issue(user.getId());
        return new LoginResponse(accessToken, user.getId(), user.getUsername(), user.getDisplayName(),
                effectivePermissions(user.getId()));
    }

    @Transactional
    public RoleResponse createRole(CreateRoleRequest request) {
        if (roleRepository.existsByCode(request.code())) {
            throw new ConflictException("角色编码已存在: " + request.code());
        }
    // 岗位与角色一一对应；先 flush 让后续 JDBC INSERT 能引用已写入的角色。flush 不等于提交。
        Role role = roleRepository.saveAndFlush(new Role(request.code(), request.name(), request.description()));
        platform.db().update("insert into sys_post(name,role_id,deleted) values(?,?,false)",request.name(),role.getId());
        return toRoleResponse(role);
    }

    public List<RoleResponse> listRoles() {
        return roleRepository.findAll().stream().map(this::toRoleResponse).toList();
    }

    @Transactional
    public RoleResponse updateRole(Long roleId, UpdateRoleRequest request) {
        Role role = getRole(roleId);
        role.setName(request.name());
        role.setDescription(request.description());
        role.setEnabled(request.enabled());
        platform.db().update("update sys_post set name=? where role_id=?",request.name(),roleId);
        platform.permissionsChanged();
        return toRoleResponse(roleRepository.save(role));
    }

    @Transactional
    public void deleteRole(Long roleId) {
        var role=getRole(roleId);
        if(platform.count("select count(*) from sys_user_role where role_id=?",roleId)>0
            ||platform.count("select count(*) from employee_profile e join sys_post p on p.id=e.post_id where p.role_id=?",roleId)>0)
            throw new ConflictException("角色或岗位仍被员工引用，请先解除关联");
        rolePermissionRepository.deleteAllByRole_Id(roleId);
        platform.db().update("update sys_post set deleted=true,name=concat('deleted_post_',id) where role_id=?",roleId);
        role.setEnabled(false);role.setDeleted(true);role.setCode("deleted_role_"+roleId);roleRepository.save(role);
        platform.permissionsChanged();
    }

    @Transactional
    public SystemResponse createSystem(CreateSystemRequest request) {
        if (systemRepository.existsByCode(request.code())) {
            throw new ConflictException("业务系统编码已存在: " + request.code());
        }
        BusinessSystem system = systemRepository.save(
                new BusinessSystem(request.code(), request.name(), request.baseUrl()));
        return toSystemResponse(system);
    }

    public List<SystemResponse> listSystems() {
        return systemRepository.findAll().stream().map(this::toSystemResponse).toList();
    }

    @Transactional
    public SystemResponse updateSystem(Long systemId, UpdateSystemRequest request) {
        BusinessSystem system = getSystem(systemId);
        system.setName(request.name());
        system.setBaseUrl(request.baseUrl());
        system.setEnabled(request.enabled());
        platform.permissionsChanged();
        return toSystemResponse(systemRepository.save(system));
    }

    @Transactional
    public void deleteSystem(Long systemId) {
        getSystem(systemId);
        List<Permission> permissions = permissionRepository.findAllBySystem_Id(systemId);
        if (permissions.stream().anyMatch(Permission::isActive)) {
            throw new ConflictException("业务系统仍有有效权限点，请先标记失效");
        }
        if (platform.count("select count(*) from sys_role_permission rp join sys_permission p on p.id=rp.permission_id where p.system_id=?", systemId) > 0) {
            throw new ConflictException("失效权限点仍被角色引用，请先回收授权");
        }
        var system=getSystem(systemId);system.setEnabled(false);system.setDeleted(true);
        system.setCode("deleted_system_"+systemId);systemRepository.save(system);
        platform.db().update("update application_access set status='DISABLED' where system_id=?",systemId);
        platform.permissionsChanged();
    }

    @Transactional
    public PermissionResponse createPermission(CreatePermissionRequest request) {
        if (request.code() == null || !request.code().matches(CreatePermissionRequest.CODE)) {
            throw new IllegalArgumentException("权限编码格式为 应用:模块:操作，小写字母、数字、下划线或连字符，最多三段");
        }
        if (permissionRepository.existsByCode(request.code())) {
            throw new ConflictException("权限编码已存在: " + request.code());
        }
        BusinessSystem system = systemRepository.findById(request.systemId())
                .orElseThrow(() -> new ResourceNotFoundException("业务系统不存在: " + request.systemId()));
        Permission permission = permissionRepository.save(
                new Permission(request.code(), request.name(), request.menuPath(), system,
                        request.type() == null ? "API" : request.type()));
        return toPermissionResponse(permission);
    }

    public List<PermissionResponse> listPermissions(Long systemId) {
        List<Permission> permissions = permissionRepository.findVisibleBySystemId(systemId);
        return permissions.stream().map(this::toPermissionResponse).toList();
    }

    @Transactional
    public PermissionResponse updatePermission(Long permissionId, UpdatePermissionRequest request) {
        Permission permission = getPermission(permissionId);
        permission.setName(request.name());
        permission.setMenuPath(request.menuPath());
        if (request.type() != null) {
            permission.setType(request.type());
        }
        if (request.active() != null && request.active() != permission.isActive()) {
            permission.setActive(request.active());
            holdersChanged(permissionId);
        }
        return toPermissionResponse(permissionRepository.save(permission));
    }

    @Transactional
    public void deletePermission(Long permissionId) {
        // UC-B03 step 6: a removed function is marked inactive, not physically deleted; existing grants are kept (5a).
        Permission permission = getPermission(permissionId);
        if (permission.isActive()) {
            permission.setActive(false);
            permissionRepository.save(permission);
            holdersChanged(permissionId);
        }
    }

    private void holdersChanged(Long permissionId) {
        platform.rows("select distinct ur.user_id from sys_user_role ur join sys_role_permission rp on rp.role_id=ur.role_id where rp.permission_id=?", permissionId)
                .forEach(row -> platform.changed(((Number) row.get("user_id")).longValue()));
    }

    // 资源是“用户与角色的关联”，不是修改角色本身；重复授予已有关系不再新增。
    // 关系变更与权限版本递增一起提交，旧 JWT 下一次校验时会因版本不一致而被拒绝。
    @Transactional
    public void assignRole(Long userId, Long roleId) {
        UserAccount user = getUser(userId);
        Role role = getRole(roleId);
        if (!userRoleRepository.existsByUser_IdAndRole_Id(userId, roleId)) {
            userRoleRepository.save(new UserRole(user, role));
            platform.changed(userId);
        }
    }

    @Transactional
    public void removeRole(Long userId, Long roleId) {
        getUser(userId);
        getRole(roleId);
        userRoleRepository.deleteByUser_IdAndRole_Id(userId, roleId);
        platform.changed(userId);
    }

    @Transactional
    public void assignPermission(Long roleId, Long permissionId) {
        Role role = getRole(roleId);
        Permission permission = getPermission(permissionId);
        if (!permission.isActive()) {
            throw new ConflictException("权限点已失效，不能再授予: " + permission.getCode());
        }
        if (!rolePermissionRepository.existsByRole_IdAndPermission_Id(roleId, permissionId)) {
            rolePermissionRepository.save(new RolePermission(role, permission));
            userRoleRepository.findAllByRole_Id(roleId).forEach(link->platform.changed(link.getUser().getId()));
        }
    }

    @Transactional
    public void removePermission(Long roleId, Long permissionId) {
        getRole(roleId);
        getPermission(permissionId);
        rolePermissionRepository.deleteByRole_IdAndPermission_Id(roleId, permissionId);
        userRoleRepository.findAllByRole_Id(roleId).forEach(link->platform.changed(link.getUser().getId()));
    }

    public List<RoleResponse> listUserRoles(Long userId) {
        getUser(userId);
        return userRoleRepository.findAllByUser_Id(userId).stream()
                .map(UserRole::getRole)
                .map(this::toRoleResponse)
                .toList();
    }

    public List<PermissionResponse> listRolePermissions(Long roleId) {
        getRole(roleId);
        return rolePermissionRepository.findAllByRole_Id(roleId).stream()
                .map(RolePermission::getPermission)
                .map(this::toPermissionResponse)
                .toList();
    }

    // RBAC0：遍历用户的启用角色，将有效权限且所属系统启用的权限按 code 去重，形成并集。
    // 没有角色继承、互斥角色或直接给用户授予权限的分支；平台管理身份另由显式边界判断。
    // 当前是逐角色查询关联，并非单条聚合 SQL；角色多时存在多次数据库往返。
    public List<PermissionResponse> effectivePermissions(Long userId) {
        UserAccount user = getUser(userId);
        if (!user.isEnabled()) {
            return List.of();
        }

        Map<String, PermissionResponse> permissionsByCode = new LinkedHashMap<>();
        userRoleRepository.findAllByUser_Id(userId).stream()
                .map(UserRole::getRole)
                .filter(Role::isEnabled)
                .forEach(role -> rolePermissionRepository.findAllByRole_Id(role.getId()).stream()
                        .map(RolePermission::getPermission)
                        .filter(permission -> permission.isActive() && permission.getSystem().isEnabled())
                        .forEach(permission -> permissionsByCode.putIfAbsent(
                                permission.getCode(), toPermissionResponse(permission))));
        return List.copyOf(permissionsByCode.values());
    }

    public boolean hasPermission(Long userId, String permissionCode) {
        return effectivePermissions(userId).stream()
                .anyMatch(permission -> permission.code().equals(permissionCode));
    }

    public void assertPermission(Long userId, String permissionCode) {
        if (!hasPermission(userId, permissionCode)) {
            platform.audit(userId, "ACCESS_DENIED", permissionCode, "DENIED", userId);
            throw new ForbiddenException("用户没有权限: " + permissionCode);
        }
    }

    /** Enabled roles of the user that currently carry the permission through an enabled system; the basis of an allow decision. */
    public List<String> grantingRoles(Long userId, String permissionCode) {
        return platform.rows("select distinct r.code from sys_user_role ur join sys_role r on r.id=ur.role_id join sys_role_permission rp on rp.role_id=r.id join sys_permission p on p.id=rp.permission_id join biz_system s on s.id=p.system_id where ur.user_id=? and p.code=? and r.enabled=true and p.active=true and s.enabled=true order by r.code", userId, permissionCode)
                .stream().map(row -> (String) row.get("code")).toList();
    }

    // Read-write: token validation records session activity. H2 ignores the class-level read-only flag, MySQL rejects the write.
    // 虽然名字像查询，令牌校验会更新 session_activity，必须使用可写事务。
    @Transactional
    public Long requirePermission(String authorizationHeader, String permissionCode) {
        Long userId = jwtTokenService.requireUserId(authorizationHeader);
        assertPermission(userId, permissionCode);
        return userId;
    }

    private UserAccount getUserForUpdate(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在: " + userId));
    }

    private UserAccount getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在: " + userId));
    }

    private Role getRole(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("角色不存在: " + roleId));
    }

    private BusinessSystem getSystem(Long systemId) {
        return systemRepository.findById(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("业务系统不存在: " + systemId));
    }

    private Permission getPermission(Long permissionId) {
        return permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("权限不存在: " + permissionId));
    }

    // 在服务事务内完成 DTO 映射，避免把懒加载关联交给 Web 序列化；响应中不含密码哈希。
    private UserResponse toUserResponse(UserAccount user) {
        return CloneFactory.copy(new UserResponse(), user);
    }

    private RoleResponse toRoleResponse(Role role) {
        return CloneFactory.copy(new RoleResponse(), role);
    }

    private SystemResponse toSystemResponse(BusinessSystem system) {
        return CloneFactory.copy(new SystemResponse(), system);
    }

    private PermissionResponse toPermissionResponse(Permission permission) {
        return CloneFactory.copy(new PermissionResponse(), permission);
    }
}
