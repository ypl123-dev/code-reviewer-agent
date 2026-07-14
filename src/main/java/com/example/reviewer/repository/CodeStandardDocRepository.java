package com.example.reviewer.repository;

import com.example.reviewer.model.entity.CodeStandardDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeStandardDocRepository extends JpaRepository<CodeStandardDoc, Long> {
    List<CodeStandardDoc> findByCategory(String category);
}
