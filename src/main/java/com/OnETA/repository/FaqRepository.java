package com.OnETA.repository;

import com.OnETA.entity.Faq;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FaqRepository extends JpaRepository<Faq, Long> {
    List<Faq> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();
}
