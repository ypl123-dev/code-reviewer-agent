package com.example.reviewer.model.dto;

import lombok.Data;

/**
 * GitLab / Gitea Webhook 事件载荷
 */
@Data
public class WebhookPayload {

    private String eventType;     // merge_request / push
    private String projectKey;    // 项目标识：namespace/repo
    private Long projectId;       // Git 平台项目 ID
    private Long mrIid;           // MR/PR IID
    private String commitSha;
    private String sourceBranch;
    private String targetBranch;
    private String title;
    private String description;

    public static WebhookPayload of(String eventType, String projectKey, Long projectId,
                                     Long mrIid, String commitSha, String source, String target,
                                     String title, String desc) {
        WebhookPayload p = new WebhookPayload();
        p.eventType = eventType;
        p.projectKey = projectKey;
        p.projectId = projectId;
        p.mrIid = mrIid;
        p.commitSha = commitSha;
        p.sourceBranch = source;
        p.targetBranch = target;
        p.title = title;
        p.description = desc;
        return p;
    }
}
