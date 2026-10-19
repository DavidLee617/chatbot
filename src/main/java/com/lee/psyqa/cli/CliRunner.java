package com.lee.psyqa.cli;

import com.lee.psyqa.answer.AnswerService;
import com.lee.psyqa.eval.ConversationEval;
import com.lee.psyqa.eval.CrisisConversationEval;
import com.lee.psyqa.eval.EvalService;
import com.lee.psyqa.eval.MemoryEval;
import com.lee.psyqa.ingest.IngestService;
import com.lee.psyqa.model.AskResult;
import com.lee.psyqa.model.SourceRef;
import com.lee.psyqa.retrieval.RetrievalService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 解析 ingest / ask / eval 命令。不带参数时什么都不做（Web 模式）。
 * 退出码通过 ExitCodeGenerator 交给 main（≈ Main 返回 int）。
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final String USAGE = """
            用法：
              java -jar psyqa.jar ingest [--book <file>]
              java -jar psyqa.jar ask "荣格说的集体无意识是什么？"
              java -jar psyqa.jar eval [--file eval/questions.jsonl] [--k 5] [--lang zh|en] [--mode original|translated|both] [--rerank on|off]
              java -jar psyqa.jar eval --conversations [--file eval/conversations.jsonl] [--no-rewrite] [--mode ...]
              java -jar psyqa.jar eval --crisis-conversations [--file eval/crisis-conversations.jsonl]
              java -jar psyqa.jar eval --memory [--file eval/memory-scenarios.jsonl]
              java -jar psyqa.jar                      # 启动 Web 服务
            """;

    private final IngestService ingestService;
    private final AnswerService answerService;
    private final EvalService evalService;
    private final ConversationEval conversationEval;
    private final CrisisConversationEval crisisConversationEval;
    private final MemoryEval memoryEval;
    private int exitCode = 0;

    public CliRunner(IngestService ingestService, AnswerService answerService, EvalService evalService,
                     ConversationEval conversationEval, CrisisConversationEval crisisConversationEval,
                     MemoryEval memoryEval) {
        this.ingestService = ingestService;
        this.answerService = answerService;
        this.evalService = evalService;
        this.conversationEval = conversationEval;
        this.crisisConversationEval = crisisConversationEval;
        this.memoryEval = memoryEval;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            return;
        }
        switch (args[0]) {
            case "ingest" -> ingest(args);
            case "eval" -> eval(args);
            case "ask" -> {
                if (args.length < 2 || args[1].isBlank()) {
                    usage();
                } else {
                    ask(args[1]);
                }
            }
            default -> usage();
        }
    }

    private void ingest(String[] args) throws Exception {
        String book = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--book") && i + 1 < args.length) {
                book = args[++i];
            } else if (args[i].startsWith("--book=")) {
                book = args[i].substring("--book=".length());
            }
        }
        IngestService.Summary s = ingestService.ingest(book);
        System.out.printf("完成：%d 本书，%d 块，失败 %d 块%n", s.books(), s.chunks(), s.failedChunkIds().size());
        if (!s.failedChunkIds().isEmpty()) {
            System.out.println("失败的 chunkId：" + s.failedChunkIds());
            exitCode = 1;
        }
    }

    private void eval(String[] args) throws Exception {
        Path file = null;
        int k = 5;
        boolean english = false;
        boolean conversations = false;
        boolean crisisConversations = false;
        boolean memory = false;
        boolean rewrite = true;
        Boolean rerank = null;   // null = 用配置里的 psyqa.retrieval.rerank.enabled
        RetrievalService.QueryMode mode = null;   // null = 用配置里的 psyqa.retrieval.query-mode
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--file" -> file = Path.of(args[++i]);
                case "--k" -> k = Integer.parseInt(args[++i]);
                case "--lang" -> english = "en".equalsIgnoreCase(args[++i]);
                case "--mode" -> mode = RetrievalService.QueryMode.valueOf(args[++i].toUpperCase());
                case "--conversations" -> conversations = true;
                case "--crisis-conversations" -> crisisConversations = true;
                case "--memory" -> memory = true;
                case "--no-rewrite" -> rewrite = false;
                case "--rerank" -> rerank = "on".equalsIgnoreCase(args[++i]);
                default -> {
                    usage();
                    return;
                }
            }
        }
        if (memory) {
            MemoryEval.Outcome out = memoryEval.run(file == null ? Path.of("eval/memory-scenarios.jsonl") : file, Path.of("eval/results"));
            System.out.print(out.summary());
            System.out.println("完整报告：" + out.markdownFile());
            return;
        }
        if (crisisConversations) {
            CrisisConversationEval.Outcome out = crisisConversationEval.run(
                    file == null ? Path.of("eval/crisis-conversations.jsonl") : file, Path.of("eval/results"));
            System.out.print(out.summary());
            System.out.println("完整报告：" + out.markdownFile());
            return;
        }
        if (conversations) {
            ConversationEval.Outcome out = conversationEval.run(file == null ? Path.of("eval/conversations.jsonl") : file,
                    k, rewrite, mode, rerank, Path.of("eval/results"));
            System.out.print(out.summary());
            System.out.println("完整报告：" + out.markdownFile());
            return;
        }
        if (file == null) {
            file = Path.of("eval/questions.jsonl");
        }
        EvalService.Outcome out = evalService.run(new EvalService.Options(file, k, english, mode, rerank, Path.of("eval/results")));
        System.out.println();
        System.out.print(out.report().summary());
        System.out.println("完整报告：" + out.markdownFile());
    }

    private void ask(String question) {
        AskResult r = answerService.ask(question);
        System.out.println();
        if (r.route() == AskResult.Route.CRISIS) {
            System.out.println("【请注意】");
        }
        System.out.println(r.answer());
        if (!r.sources().isEmpty()) {
            System.out.println("\n参考来源：");
            for (SourceRef s : r.sources()) {
                System.out.printf("  [%d] 《%s》%s，%s（相似度 %.2f）%n", s.index(), s.book(), s.author(), s.chapter(), s.score());
            }
        }
    }

    private void usage() {
        System.err.print(USAGE);
        exitCode = 2;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
