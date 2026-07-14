package com.example.reviewer.controller;

import com.example.reviewer.model.dto.DiffChunk;
import com.example.reviewer.service.agent.CodeReviewService;
import com.example.reviewer.service.diff.DiffChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * SSE 流式接口
 *
 * 简历亮点：
 * - SSE 流式输出（面试重点：背压、断线重连、Token 边界）
 * - 多轮对话支持（ChatMemory 通过 sessionId 隔离）
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final CodeReviewService codeReviewService;
    private final DiffChunker diffChunker;

    /**
     * 对话式追问 - 基于 sessionId 隔离上下文
     *
     * @param sessionId 会话 ID（建议关联到 PR）
     * @param message   用户消息
     */
    @GetMapping(value = "/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @PathVariable String sessionId,
            @RequestParam String message) {
        log.info("对话: sessionId={}, msg 长度={}", sessionId, message.length());

        DiffChunk chunk = new DiffChunk("chat", "", "java", "", message, "", 0);

        return codeReviewService.reviewChunkStream(chunk, sessionId)
                .map(content -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString())
                        .event("message")
                        .data(content)
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[对话完成]")
                        .build()))
                .onErrorResume(e -> {
                    log.error("SSE 错误", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("[stream error: " + e.getMessage() + "]")
                            .build());
                });
    }

    /**
     * 直接对代码片段进行流式审查（不依赖 Git 平台，方便本地测试）
     */
    @PostMapping(value = "/review", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reviewCode(
            @RequestParam(required = false, defaultValue = "default") String sessionId,
            @RequestBody String codeDiff) {
        log.info("代码审查: sessionId={}, diff 长度={}", sessionId, codeDiff.length());

        List<DiffChunk> chunks = diffChunker.chunk(codeDiff);
        if (chunks.isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("message")
                    .data("未检测到代码变更")
                    .build());
        }

        Flux<ServerSentEvent<String>> header = Flux.just(ServerSentEvent.<String>builder()
                .event("start")
                .data("开始审查，共 " + chunks.size() + " 个 chunk")
                .build());

        Flux<ServerSentEvent<String>> body = Flux.fromIterable(chunks)
                .concatMap(chunk -> {
                    String fileLabel = "## " + chunk.filePath() + "\n";
                    Flux<ServerSentEvent<String>> fileHeader = Flux.just(ServerSentEvent.<String>builder()
                            .event("file")
                            .data(fileLabel)
                            .build());

                    Flux<ServerSentEvent<String>> content = codeReviewService.reviewChunkStream(chunk, sessionId)
                            .map(c -> ServerSentEvent.<String>builder()
                                    .event("message")
                                    .data(c)
                                    .build());

                    return Flux.concat(fileHeader, content);
                });

        Flux<ServerSentEvent<String>> footer = Flux.just(ServerSentEvent.<String>builder()
                .event("done")
                .data("[审查完成]")
                .build());

        return Flux.concat(header, body, footer)
                .onErrorResume(e -> {
                    log.error("SSE 错误", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("[stream error: " + e.getMessage() + "]")
                            .build());
                });
    }
}
