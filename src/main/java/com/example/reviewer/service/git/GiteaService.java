package com.example.reviewer.service.git;

import com.example.reviewer.config.ReviewerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Gitea API 封装服务
 *
 * 职责：
 * 1. 通过 Gitea REST API 获取 PR/MR 的 diff 内容
 * 2. 将审查结果作为评论回写到 PR
 *
 * Gitea API 文档：https://docs.gitea.com/api
 *
 * 关键接口：
 * - GET /api/v1/repos/{owner}/{repo}/pulls/{index}.diff  获取 diff
 * - POST /api/v1/repos/{owner}/{repo}/issues/{index}/comments  评论
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiteaService {

    private final ReviewerProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取 PR 的 diff 内容
     *
     * @param repoFullName 仓库全名（owner/repo）
     * @param prNumber     PR 编号
     * @return unified diff 文本
     */
    public String fetchPrDiff(String repoFullName, int prNumber) {
        String url = String.format("%s/api/v1/repos/%s/pulls/%d.diff",
                properties.getGit().getBaseUrl(), repoFullName, prNumber);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getGit().getToken());
        headers.setAccept(MediaType.parseMediaTypes("text/plain"));

        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new RuntimeException("获取 diff 失败: " + resp.getStatusCode());
        }
        log.info("获取 PR diff 成功: {}/{}, 长度={}", repoFullName, prNumber, resp.getBody().length());
        return resp.getBody();
    }

    /**
     * 将审查结果作为评论回写到 PR
     *
     * @param repoFullName 仓库全名
     * @param prNumber     PR 编号
     * @param reviewBody   审查内容（Markdown）
     * @return 评论 ID
     */
    public Long postReviewComment(String repoFullName, int prNumber, String reviewBody) {
        String url = String.format("%s/api/v1/repos/%s/issues/%d/comments",
                properties.getGit().getBaseUrl(), repoFullName, prNumber);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getGit().getToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 转义 JSON 字符串
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(
                    java.util.Map.of("body", reviewBody));
        } catch (Exception e) {
            throw new RuntimeException("序列化评论失败", e);
        }

        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            log.warn("评论回写失败: {} - {}", resp.getStatusCode(), resp.getBody());
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(resp.getBody());
            Long commentId = node.path("id").asLong();
            log.info("审查评论已回写: {}/{}, commentId={}", repoFullName, prNumber, commentId);
            return commentId;
        } catch (Exception e) {
            log.warn("解析评论响应失败（但评论可能已成功）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 Gitea webhook payload，提取 PR 信息
     *
     * @param payload webhook 原始 JSON
     * @return PR 信息（repoFullName、prNumber、action）
     */
    public PullRequestInfo parsePullRequestPayload(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = root.path("action").asText("");

            // 只处理打开/更新操作
            if (!"opened".equals(action) && !"updated".equals(action) && !"synchronized".equals(action)) {
                return null;
            }

            JsonNode prNode = root.path("pull_request");
            if (prNode.isMissingNode()) {
                return null;
            }

            int prNumber = prNode.path("number").asInt();
            JsonNode repoNode = root.path("repository");
            String fullName = repoNode.path("full_name").asText();

            String sourceBranch = prNode.path("head").path("ref").asText();
            String targetBranch = prNode.path("base").path("ref").asText();
            String title = prNode.path("title").asText();

            return new PullRequestInfo(fullName, prNumber, action, sourceBranch, targetBranch, title);
        } catch (Exception e) {
            log.error("解析 webhook payload 失败", e);
            return null;
        }
    }

    /**
     * PR 信息载体
     */
    public record PullRequestInfo(
            String repoFullName,
            int prNumber,
            String action,
            String sourceBranch,
            String targetBranch,
            String title
    ) {}
}
