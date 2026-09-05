package com.allen.questionbank.repository;

import com.allen.questionbank.entity.QuestionBank;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionBankRepository extends JpaRepository<QuestionBank, Long> {}
