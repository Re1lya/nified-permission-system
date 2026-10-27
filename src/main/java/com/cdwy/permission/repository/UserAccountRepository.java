package com.cdwy.permission.repository;

import com.cdwy.permission.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 数据库访问示例：JpaRepository 由 Spring 生成实现，JPQL 查询写的是 Java 属性，不是表列名。
 * PESSIMISTIC_WRITE 请求数据库悲观写锁；需在事务中调用，锁随事务结束释放。
 * 它保护选中的账号行，不代表给整个系统加锁，也不能替代唯一约束。
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    boolean existsByAdministratorTrue();

    boolean existsByUsername(String username);

    Optional<UserAccount> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccount user where user.username = :username")
    Optional<UserAccount> findByUsernameForUpdate(@Param("username") String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccount user where user.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") Long id);
}
