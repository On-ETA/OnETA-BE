package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email; // 어떤 사용자의 토큰인지 확인

    @Column(nullable = false, length = 512) // JWT가 길 수 있으므로 넉넉하게
    private String token;

    @Builder
    public RefreshToken(String email, String token) {
        this.email = email;
        this.token = token;
    }

    // 토큰 갱신 메서드
    public void updateToken(String token) {
        this.token = token;
    }
}