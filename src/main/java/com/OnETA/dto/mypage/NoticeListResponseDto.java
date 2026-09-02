package com.OnETA.dto.mypage;

import com.OnETA.entity.Notice;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class NoticeListResponseDto {
    private Long id;
    private String title;
    private String author;
    private Integer viewCount;
    private LocalDateTime createdAt;

    public static NoticeListResponseDto from(Notice notice) {
        return NoticeListResponseDto.builder()
                .id(notice.getId())
                .title(notice.getTitle())
                .author(notice.getAuthor())
                .viewCount(notice.getViewCount())
                .createdAt(notice.getCreatedAt())
                .build();
    }
}
