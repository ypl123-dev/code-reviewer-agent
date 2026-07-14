package com.example.reviewer.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 审查请求
 */
@Data
@Builder
public class ReviewRequest {

    private String projectKey;
    private Long projectId;
    private Long mrIid;
    private String commitSha;
    private String sourceBranch;
    private String targetBranch;
    private String title;

    public static ReviewRequest from(WebhookPayload payload) {
        return ReviewRequest.builder()
                .projectKey(payload.getProjectKey())
                .projectId(payload.getProjectId())
                .mrIid(payload.getMrIid())
                .commitSha(payload.getCommitSha())
                .sourceBranch(payload.getSourceBranch())
                .targetBranch(payload.getTargetBranch())
                .title(payload.getTitle())
                .build();
    }
}
