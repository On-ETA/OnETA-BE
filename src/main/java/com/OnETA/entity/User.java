package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor // 롬복: 기본 생성자를 자동으로 만들어줌
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // MySQL의 Auto Increment를 사용하여 ID를 1씩 자동 증가시킵니다.
    private Long id;

    @Column(nullable = false, unique = true) // 이메일은 필수이며, 중복X
    private String email;

    @Column
    private String password;

    @Column(nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // UserDeviceToken 의 1:N 양방향 연관관계
    // cascade = ALL, orphanRemoval = true 로 설정하여 User 삭제 시 토큰들도 함께 삭제
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserDeviceToken> deviceTokens = new ArrayList<>();

    @Builder
    public User(String email, String password, String nickname, Role role) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.role = role;
    }

    public void upgradeToUser() {
        this.role = Role.USER;
    }

    public void updatePassword(String newPassword) {
        this.password = newPassword;
    }

    public void updateNickname(String newNickname) {
        this.nickname = newNickname;
    }

    public void addDeviceToken(UserDeviceToken token) {
        this.deviceTokens.add(token);
    }
}
