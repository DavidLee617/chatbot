package com.lee.psyqa.web;

import com.lee.psyqa.memory.Memory;
import com.lee.psyqa.memory.MemoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 用户查看 / 删除自己的长期记忆（T8）。uid 就是匿名用户 id：知道它就能看到对应的记忆，没有登录前只能做到这样。
 * JDBC 是阻塞调用，放到 boundedElastic 上（≈ Task.Run 包住同步 IO）。
 */
@RestController
public class MemoryController {

    /** 返回给前端的字段：不含向量。 */
    public record MemoryView(UUID id, String kind, String content, Instant updatedAt) {}

    private final MemoryService memory;

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    @GetMapping("/api/memory")
    public Mono<ResponseEntity<List<MemoryView>>> list(@RequestParam String uid) {
        String u = ChatController.validId(uid);
        if (u == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(() -> ResponseEntity.ok(memory.list(u).stream().map(MemoryController::view).toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/api/memory/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id, @RequestParam String uid) {
        String u = ChatController.validId(uid);
        if (u == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(() -> memory.delete(u, id)
                        ? ResponseEntity.noContent().<Void>build()
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).<Void>build())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/api/memory")
    public Mono<ResponseEntity<Void>> deleteAll(@RequestParam String uid) {
        String u = ChatController.validId(uid);
        if (u == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(() -> {
            memory.deleteAll(u);
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static MemoryView view(Memory m) {
        return new MemoryView(m.id(), m.kind().name(), m.content(), m.updatedAt());
    }
}
