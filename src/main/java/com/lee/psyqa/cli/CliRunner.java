package com.lee.psyqa.cli;

import com.lee.psyqa.answer.AnswerService;
import com.lee.psyqa.ingest.IngestService;
import com.lee.psyqa.model.AskResult;
import com.lee.psyqa.model.SourceRef;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * 解析 ingest / ask 命令。不带参数时什么都不做（Web 模式）。
 * 退出码通过 ExitCodeGenerator 交给 main（≈ Main 返回 int）。
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final String USAGE = """
            用法：
              java -jar psyqa.jar ingest [--book <file>]
              java -jar psyqa.jar ask "荣格说的集体无意识是什么？"
              java -jar psyqa.jar                      # 启动 Web 服务
            """;

    private final IngestService ingestService;
    private final AnswerService answerService;
    private int exitCode = 0;

    public CliRunner(IngestService ingestService, AnswerService answerService) {
        this.ingestService = ingestService;
        this.answerService = answerService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            return;
        }
        switch (args[0]) {
            case "ingest" -> ingest(args);
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
