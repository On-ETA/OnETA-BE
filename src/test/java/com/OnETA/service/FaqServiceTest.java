package com.OnETA.service;

import com.OnETA.controller.MyPageController;
import com.OnETA.dto.mypage.FaqResponseDto;
import com.OnETA.entity.Faq;
import com.OnETA.repository.FaqRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FaqServiceTest {
    private final FaqRepository repository = mock(FaqRepository.class);
    private final FaqService service = new FaqService(repository);
    private final MyPageController controller = new MyPageController(mock(MyPageService.class), service);

    @Test
    void returnsQuestionsAndAnswersInRepositoryOrderWithSuccessEnvelope() {
        Faq first = new Faq("알림은 어떻게 설정하나요?", "알림 화면에서 설정하세요.\n저장 후 적용됩니다.", 1, true);
        Faq second = new Faq("주소는 어떻게 변경하나요?", "주소 관리 화면에서 변경하세요.", 2, true);
        ReflectionTestUtils.setField(first, "id", 7L);
        ReflectionTestUtils.setField(second, "id", 3L);
        when(repository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(first, second));

        var response = controller.getFaqList();

        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getData()).extracting(FaqResponseDto::getId).containsExactly(7L, 3L);
        assertThat(response.getData().get(0).getQuestion()).isEqualTo(first.getQuestion());
        assertThat(response.getData().get(0).getAnswer()).isEqualTo(first.getAnswer());
    }

    @Test
    void returnsEmptyArrayInsteadOfNullWhenNoFaqsArePublished() {
        when(repository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());

        var response = controller.getFaqList();

        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getData()).isNotNull().isEmpty();
    }
}
