package com.allen.cloud.bank;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionDraftRepository extends JpaRepository<QuestionDraft, Long> {

    List<QuestionDraft> findByBankIdOrderById(Long bankId);
}
