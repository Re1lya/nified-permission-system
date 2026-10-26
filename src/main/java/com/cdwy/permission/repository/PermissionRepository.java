package com.cdwy.permission.repository;

import com.cdwy.permission.entity.Permission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    boolean existsByCode(String code);

    // Join the visible parent before mapping responses; retired permissions of a deleted system remain historical rows.
    @org.springframework.data.jpa.repository.Query("select p from Permission p join fetch p.system s where s.deleted=false and (:systemId is null or s.id=:systemId)")
    List<Permission> findVisibleBySystemId(@org.springframework.data.repository.query.Param("systemId") Long systemId);

    List<Permission> findAllBySystem_Id(Long systemId);
}
