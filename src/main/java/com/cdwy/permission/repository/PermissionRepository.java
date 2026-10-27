package com.cdwy.permission.repository;

import com.cdwy.permission.entity.Permission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 权限目录查询。join fetch 一起装载所属系统，便于服务内映射 DTO，
 * 并过滤已逻辑删除的业务系统；历史权限行仍可能留在数据库中。
 */
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    boolean existsByCode(String code);

    // Join the visible parent before mapping responses; retired permissions of a deleted system remain historical rows.
    @org.springframework.data.jpa.repository.Query("select p from Permission p join fetch p.system s where s.deleted=false and (:systemId is null or s.id=:systemId)")
    List<Permission> findVisibleBySystemId(@org.springframework.data.repository.query.Param("systemId") Long systemId);

    List<Permission> findAllBySystem_Id(Long systemId);
}
