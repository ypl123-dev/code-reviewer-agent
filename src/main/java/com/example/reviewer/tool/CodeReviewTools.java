package com.example.reviewer.tool;

import com.example.reviewer.model.entity.CodeStandardDoc;
import com.example.reviewer.model.entity.ReviewHistoryIssue;
import com.example.reviewer.repository.CodeStandardDocRepository;
import com.example.reviewer.repository.ReviewHistoryIssueRepository;
import com.example.reviewer.service.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 代码审查 Function Calling 工具集
 *
 * LLM 通过这些工具增强审查能力：
 * 1. queryCodeStandard        - 查询团队代码规范
 * 2. searchSimilarIssues      - 检索历史类似问题
 * 3. queryProjectConfig       - 查询项目配置信息
 * 4. runStaticAnalysis        - 运行静态分析规则
 *
 * 设计说明：
 * - 用 @Tool 暴露能力，由 LLM 根据 description 自主决定是否调用
 * - 工具返回结构化文本，降低 LLM 解析成本
 * - 注意：runStaticAnalysis 目前为演示用的简化规则匹配，
 *   生产环境应接入 Checkstyle / SpotBugs 等真实分析器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeReviewTools {

    private final CodeStandardDocRepository standardRepository;
    private final ReviewHistoryIssueRepository issueRepository;
    private final KnowledgeBaseService knowledgeBaseService;

    @Tool(description = "查询团队编码规范。根据类别返回相关的规范条款。类别支持：命名 / 异常 / 日志 / 并发 / 安全 / 注释")
    public String queryCodeStandard(
            @ToolParam(description = "规范类别") String category) {
        log.info("[Tool] 查询代码规范: category={}", category);
        List<CodeStandardDoc> docs = standardRepository.findByCategory(category);
        if (docs.isEmpty()) {
            return "未找到类别 " + category + " 的规范";
        }
        return docs.stream()
                .map(d -> "【" + d.getTitle() + "】\n" + d.getContent())
                .collect(Collectors.joining("\n\n"));
    }

    @Tool(description = "语义检索历史类似 Bug 和修复方案。输入代码片段，返回最相关的历史 Issue")
    public String searchSimilarIssues(
            @ToolParam(description = "代码片段") String codeSnippet) {
        log.info("[Tool] 检索历史 Issue: snippet 长度={}", codeSnippet.length());
        List<ReviewHistoryIssue> issues = knowledgeBaseService.searchSimilarIssues(codeSnippet, 5);
        if (issues.isEmpty()) {
            return "未找到相关历史 Issue";
        }
        return issues.stream()
                .map(i -> String.format("【%s】(%s)\n问题：%s\n方案：%s",
                        i.getIssueType(),
                        i.getSeverity(),
                        i.getTitle(),
                        i.getFixSolution()))
                .collect(Collectors.joining("\n---\n"));
    }

    @Tool(description = "查询项目依赖版本、配置项。用于检查 diff 中使用的 API 是否与项目版本匹配")
    public String queryProjectConfig(
            @ToolParam(description = "项目标识，例如 namespace/repo") String projectKey) {
        log.info("[Tool] 查询项目配置: project={}", projectKey);
        // 实际项目可以从 project_config 表查，这里返回简化示例
        return """
                项目 %s 的关键配置：
                - Spring Boot: 3.3.5
                - JDK: 17
                - Spring AI: 1.0.0
                - PostgreSQL: 15
                - 持久层: Spring Data JPA
                - 缓存: Redis 7
                """.formatted(projectKey);
    }

    @Tool(description = "对指定代码运行静态分析规则检查。返回检测到的问题列表")
    public String runStaticAnalysis(
            @ToolParam(description = "待检查的代码内容") String code,
            @ToolParam(description = "代码语言：java / kotlin / python") String language) {
        log.info("[Tool] 静态分析: language={}, code 长度={}", language, code.length());
        StringBuilder result = new StringBuilder();
        result.append("静态分析结果（").append(language).append("）：\n");

        // 简化版本地规则引擎（实际项目可对接 SonarQube）
        if (code.contains("System.out.println")) {
            result.append("- [警告] 使用 System.out.println，应改用日志框架 SLF4J\n");
        }
        if (code.contains("printStackTrace")) {
            result.append("- [严重] 调用 printStackTrace 吞没异常堆栈\n");
        }
        if (code.contains("Thread.sleep")) {
            result.append("- [警告] 使用 Thread.sleep 阻塞调用\n");
        }
        if (code.contains("@Autowired") && code.contains("private")) {
            result.append("- [建议] 字段注入不推荐，建议构造器注入\n");
        }
        if (code.contains("new Date()") || code.contains("new SimpleDateFormat")) {
            result.append("- [警告] 使用非线程安全日期 API，建议 java.time\n");
        }
        if (code.matches(".*\\(\\)\\s*\\{\\s*//\\s*TODO.*")) {
            result.append("- [建议] 包含 TODO 注释未处理\n");
        }
        if (result.toString().endsWith("：\n")) {
            result.append("- 未发现明显静态问题");
        }
        return result.toString();
    }
}
