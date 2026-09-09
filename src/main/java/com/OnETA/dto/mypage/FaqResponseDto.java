package com.OnETA.dto.mypage;

import com.OnETA.entity.Faq;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FaqResponseDto {
    private Long id;
    private String question;
    private String answer;

    public static FaqResponseDto from(Faq faq) {
        return FaqResponseDto.builder()
                .id(faq.getId())
                .question(faq.getQuestion())
                .answer(faq.getAnswer())
                .build();
    }
}
