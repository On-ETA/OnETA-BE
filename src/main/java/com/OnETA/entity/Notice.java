package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "notices")
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private Integer viewCount = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Notice(String title, String content, String author) {
        this.title = title;
        this.content = content;
        this.author = author;
        this.viewCount = 0;
    }

    public void incrementViewCount() {
        this.viewCount++;
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
