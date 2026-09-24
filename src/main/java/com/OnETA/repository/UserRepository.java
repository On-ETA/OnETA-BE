package com.OnETA.repository;

import com.OnETA.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

// JpaRepository<다룰 엔티티 클래스, 그 엔티티의 ID 타입>
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.email = :email")
    Optional<User> findForSignupByEmail(@org.springframework.data.repository.query.Param("email") String email);
}