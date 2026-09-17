package com.example.reviewer.service.diff;

import com.example.reviewer.common.TokenCounter;
import com.example.reviewer.config.ReviewerProperties;
import com.example.reviewer.model.dto.DiffChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiffChunker 单元测试
 *
 * 覆盖：正常多文件 diff、多 hunk、超长 hunk 滑窗、空输入、非 diff 格式、上下文收集
 */
class DiffChunkerTest {

    private DiffChunker chunker;

    @BeforeEach
    void setUp() {
        TokenCounter tokenCounter = new TokenCounter();
        ReviewerProperties properties = new ReviewerProperties();
        ReviewerProperties.ReviewConfig review = new ReviewerProperties.ReviewConfig();
        review.setMaxTokenPerChunk(4000);
        review.setContextLines(3);
        review.setMaxFilesPerReview(30);
        properties.setReview(review);
        chunker = new DiffChunker(tokenCounter, properties);
    }

    @Test
    @DisplayName("空输入返回空列表")
    void emptyInput() {
        assertTrue(chunker.chunk(null).isEmpty());
        assertTrue(chunker.chunk("").isEmpty());
        assertTrue(chunker.chunk("   \n  ").isEmpty());
    }

    @Test
    @DisplayName("单文件单 hunk 切分为 1 个 chunk")
    void singleFileSingleHunk() {
        String diff = """
                diff --git a/src/UserService.java b/src/UserService.java
                index 1234567..abcdefg 100644
                --- a/src/UserService.java
                +++ b/src/UserService.java
                @@ -10,6 +10,8 @@ public class UserService {
                     public void save(User u) {
                -        dao.insert(u);
                +        if (u == null) throw new IllegalArgumentException();
                +        dao.insert(u);
                     }
                 }
                """;

        List<DiffChunk> chunks = chunker.chunk(diff);

        assertEquals(1, chunks.size());
        DiffChunk c = chunks.get(0);
        assertEquals("src/UserService.java", c.filePath());
        assertEquals("java", c.language());
        assertTrue(c.content().contains("@@ -10,6 +10,8 @@"));
        assertTrue(c.content().contains("dao.insert(u)"));
    }

    @Test
    @DisplayName("多文件 diff 按文件切分为多个 chunk")
    void multipleFiles() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1,3 +1,3 @@
                -int a = 1;
                +int a = 2;
                diff --git a/B.java b/B.java
                --- a/B.java
                +++ b/B.java
                @@ -1,3 +1,3 @@
                -int b = 1;
                +int b = 2;
                diff --git a/C.py b/C.py
                --- a/C.py
                +++ b/C.py
                @@ -1,3 +1,3 @@
                -x = 1
                +x = 2
                """;

        List<DiffChunk> chunks = chunker.chunk(diff);

        assertEquals(3, chunks.size());
        assertEquals("A.java", chunks.get(0).filePath());
        assertEquals("B.java", chunks.get(1).filePath());
        assertEquals("C.py", chunks.get(2).filePath());
        // 语言识别
        assertEquals("java", chunks.get(0).language());
        assertEquals("python", chunks.get(2).language());
    }

    @Test
    @DisplayName("同一文件的多个 hunk 各自成为独立 chunk")
    void multipleHunksSameFile() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1,3 +1,3 @@
                -int a = 1;
                +int a = 2;
                @@ -50,3 +50,3 @@
                -int z = 1;
                +int z = 2;
                """;

        List<DiffChunk> chunks = chunker.chunk(diff);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).content().contains("@@ -1,3 +1,3 @@"));
        assertTrue(chunks.get(1).content().contains("@@ -50,3 +50,3 @@"));
        // 两个 hunk 属于同一文件
        assertEquals(chunks.get(0).filePath(), chunks.get(1).filePath());
    }

    @Test
    @DisplayName("超长 hunk 触发滑动窗口，切成多个 chunk 且每个不超过上限")
    void longHunkTriggersSlidingWindow() {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/Big.java b/Big.java\n");
        sb.append("--- a/Big.java\n");
        sb.append("+++ b/Big.java\n");
        sb.append("@@ -1,2000 +1,2000 @@\n");
        // 制造足够长的内容，确保超过 4000 token
        for (int i = 0; i < 1200; i++) {
            sb.append("+    private void method").append(i).append("() { return; }\n");
        }

        List<DiffChunk> chunks = chunker.chunk(sb.toString());

        assertTrue(chunks.size() > 1, "超长 hunk 应被切分为多个 chunk");
        for (DiffChunk c : chunks) {
            assertTrue(c.estimatedTokens() <= 4000,
                    "单 chunk token 超限: " + c.estimatedTokens());
        }
    }

    @Test
    @DisplayName("非 diff 格式输入按整个代码片段审查")
    void nonDiffInputFallsBackToSnippet() {
        String code = """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """;

        List<DiffChunk> chunks = chunker.chunk(code);

        assertEquals(1, chunks.size());
        assertEquals("snippet.java", chunks.get(0).filePath());
    }

    @Test
    @DisplayName("hunk 头部的方法名提示被解析为 functionName")
    void functionNameFromHunkHeader() {
        String diff = """
                diff --git a/UserService.java b/UserService.java
                --- a/UserService.java
                +++ b/UserService.java
                @@ -10,6 +10,7 @@ public void saveUser(User user) {
                -        dao.insert(user);
                +        validate(user);
                +        dao.insert(user);
                """;

        List<DiffChunk> chunks = chunker.chunk(diff);

        assertEquals(1, chunks.size());
        // hunk header 中的函数签名应被保留为上下文提示
        assertEquals("public void saveUser(User user) {", chunks.get(0).functionName());
    }

    @Test
    @DisplayName("语言识别覆盖常见后缀")
    void detectLanguage() {
        assertEquals("java", chunk("A.java"));
        assertEquals("python", chunk("B.py"));
        assertEquals("javascript", chunk("C.js"));
        assertEquals("typescript", chunk("D.ts"));
        assertEquals("go", chunk("E.go"));
        assertEquals("xml", chunk("F.xml"));
        assertEquals("yaml", chunk("G.yml"));
        assertEquals("text", chunk("H.unknown"));
    }

    private String chunk(String filename) {
        String diff = """
                diff --git a/%s b/%s
                --- a/%s
                +++ b/%s
                @@ -1,2 +1,2 @@
                -old
                +new
                """.formatted(filename, filename, filename, filename);
        return chunker.chunk(diff).get(0).language();
    }
}
