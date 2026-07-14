package com.example.reviewer.service.git;

import com.example.reviewer.common.ReviewerException;
import com.example.reviewer.config.ReviewerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Git 平台集成服务 - 支持 GitLab / Gitea
 *
 * 主要职责：
 * 1. 拉取 Merge Request 的 diff
 * 2. 回写审查意见到 PR 评论
 * 3. 查询 commit 详情
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitIntegrationService {

    private final RestTemplate gitRestTemplate;
    private final ReviewerProperties properties;

    /**
     * 拉取 MR 的 diff 内容
     *
     * @param projectId Git 平台项目 ID
     * @param mrIid     MR 内部 ID
     * @return unified diff 字符串
     */
    public String fetchMergeRequestDiff(Long projectId, Long mrIid) {
        String url = baseUrl() + diffPath(projectId, mrIid);
        log.info("拉取 MR diff: {}", url);
        try {
            HttpHeaders headers = authHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> resp = gitRestTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new ReviewerException("拉取 diff 失败: " + resp.getStatusCode());
            }
            return resp.getBody();
        } catch (Exception e) {
            log.error("拉取 MR diff 失败", e);
            throw new ReviewerException("拉取 MR diff 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 回写审查意见到 PR 评论
     */
    public void postPRComment(Long projectId, Long mrIid, String comment) {
        String url = baseUrl() + commentsPath(projectId, mrIid);
        log.info("回写 PR 评论: {}", url);
        try {
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("body", comment);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            gitRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            log.error("回写 PR 评论失败", e);
            throw new ReviewerException("回写 PR 评论失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试 Git 平台连通性
     */
    public boolean ping() {
        try {
            String url = baseUrl() + "/api/v1/version";
            HttpHeaders headers = authHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> resp = gitRestTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private String baseUrl() {
        String platform = properties.getGit().getPlatform();
        String baseUrl = properties.getGit().getBaseUrl();
        if ("gitlab".equalsIgnoreCase(platform)) {
            return baseUrl + "/api/v4";
        }
        return baseUrl + "/api/v1";  // Gitea
    }

    private String diffPath(Long projectId, Long mrIid) {
        String platform = properties.getGit().getPlatform();
        if ("gitlab".equalsIgnoreCase(platform)) {
            return "/projects/" + projectId + "/merge_requests/" + mrIid + "/diffs";
        }
        // Gitea 返回 PR 的 diff
        return "/repos/" + projectId + "/pulls/" + mrIid + ".diff";
    }

    private String commentsPath(Long projectId, Long mrIid) {
        String platform = properties.getGit().getPlatform();
        if ("gitlab".equalsIgnoreCase(platform)) {
            return "/projects/" + projectId + "/merge_requests/" + mrIid + "/notes";
        }
        return "/repos/" + projectId + "/issues/" + mrIid + "/comments";
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String token = properties.getGit().getToken();
        String platform = properties.getGit().getPlatform();
        if ("gitlab".equalsIgnoreCase(platform)) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        } else {
            headers.set("Authorization", "token " + token);
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }
}
