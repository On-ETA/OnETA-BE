package com.OnETA.service;

import com.OnETA.dto.mypage.FaqResponseDto;
import com.OnETA.repository.FaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FaqService {
    private final FaqRepository faqRepository;

    public List<FaqResponseDto> getAllFaqs() {
        return faqRepository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc().stream()
                .map(FaqResponseDto::from)
                .toList();
    }
}
