package com.example.reviewer.repository;

import com.example.reviewer.model.entity.ReviewHistoryIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewHistoryIssueRepository extends JpaRepository<ReviewHistoryIssue, Long> {
    List<ReviewHistoryIssue> findByProjectKey(String projectKey);
}
