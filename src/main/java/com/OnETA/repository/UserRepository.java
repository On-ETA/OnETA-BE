package com.OnETA.repository;

import com.OnETA.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

// JpaRepository<다룰 엔티티 클래스, 그 엔티티의 ID 타입>
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
}