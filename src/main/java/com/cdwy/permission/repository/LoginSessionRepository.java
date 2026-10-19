package com.cdwy.permission.repository;

import com.cdwy.permission.entity.LoginSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginSessionRepository extends JpaRepository<LoginSession, String> {

    @EntityGraph(attributePaths = "user")
    Optional<LoginSession> findByIdAndUser_Id(String id, Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from LoginSession session where session.id = :id and session.user.id = :userId")
    int deleteSession(@Param("id") String id, @Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from LoginSession session where session.user.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
