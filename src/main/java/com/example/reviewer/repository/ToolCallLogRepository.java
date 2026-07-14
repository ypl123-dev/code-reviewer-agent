package com.example.reviewer.repository;

import com.example.reviewer.model.entity.ToolCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {
    List<ToolCallLog> findByReviewId(Long reviewId);
}
