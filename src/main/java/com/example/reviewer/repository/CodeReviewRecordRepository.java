package com.example.reviewer.repository;

import com.example.reviewer.model.entity.CodeReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeReviewRecordRepository extends JpaRepository<CodeReviewRecord, Long> {
    List<CodeReviewRecord> findByProjectKeyAndMrIid(String projectKey, Long mrIid);
}
