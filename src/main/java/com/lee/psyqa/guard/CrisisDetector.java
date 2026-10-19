package com.lee.psyqa.guard;

import com.lee.psyqa.config.PsyQaProperties;
import com.lee.psyqa.conversation.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 危机识别，排在检索之前，和知识库无关。三层：
 * <ol>
 *   <li><b>强关键词</b>（第一人称意图，如"想自杀""kill myself"）：命中直接判危机，不看语义。安全底线，零成本、确定</li>
 *   <li><b>语义层（粗筛）</b>：问题向量分别和"危机例句""理论讨论例句"算最大余弦相似度。
 *     有历史时，最近 {@link #CONTEXT_TURNS} 轮用户原话和这一句拼成一个窗口也算一次（同一次 embedding 调用）。
 *     这一句或窗口"更像危机"（危机分 &gt; 理论分）才进入下一层；明显是理论问题的到这里就结束，不花 LLM 调用</li>
 *   <li><b>LLM 判定</b>（{@link CrisisJudge}，T7）：带上最近几轮用户原话，判断是不是用户本人有风险。
 *     调用失败时退回语义层的规则 {@link #decide}（T2 的判定，阈值 semanticThreshold）</li>
 * </ol>
 * 为什么语义层只做粗筛：2026-10-19 多轮评估发现它实际分的是"第一人称倾诉"和"第三人称理论问题"，
 * 第一人称但没有危险的话会误报（"我是不是太内向了才这么累"），指代上文的英文追问又会漏判
 * （"Same here … how I would do it"），这两件事都要读懂语义和上下文。
 * <p>
 * 语义层要多一次 embedding 调用：VectorStore.similaritySearch 只接受文本、内部自己算向量，拿不到它算好的问题向量。
 * 例句向量在第一次用到时才算（每批 ≤ 10 条，约 3 秒）并缓存，ingest 等不用它的命令不会白花这几次调用；
 * Web 模式启动完成后预热一次，第一个用户不用等。
 * <p>
 * 最近 {@link #FOLLOW_UP_TURNS} 轮里判过危机时 followUp=true：判为非危机的回答由 AnswerService 在末尾继续附上热线。
 * 不降低判定门槛：试过"followUp 时去掉绝对阈值"，一次误报之后下一轮也跟着误报（s07），会把整段对话锁住。
 * <p>
 * AnswerService 和 EvalService 都调用 {@link #detect}，保证评估测的是真实路径。
 */
@Component
public class CrisisDetector {

    private static final Logger log = LoggerFactory.getLogger(CrisisDetector.class);
    private static final int EMBED_BATCH = 10;   // text-embedding-v4 单次最多 10 条

    /**
     * 判定来自哪一层，写进日志和评估结果。
     * SEMANTIC：LLM 判定失败、按语义层规则判的危机；JUDGE：LLM 判的危机；JUDGE_SAFE：语义层觉得像、LLM 判为安全。
     */
    public enum Layer { STRONG_KEYWORD, JUDGE, SEMANTIC, JUDGE_SAFE, NONE, SEMANTIC_ERROR }

    /** 结合上下文时带上最近几轮用户原话。 */
    static final int CONTEXT_TURNS = 2;
    /** 判过危机之后，这么多轮内保持警觉（followUp）。 */
    static final int FOLLOW_UP_TURNS = 3;

    /**
     * @param crisisScore 和危机例句的最大相似度；强关键词命中或语义层出错时为 0
     * @param theoryScore 和理论讨论例句的最大相似度
     * @param contextCrisisScore 上下文窗口（最近几轮用户原话 + 这一句）的危机分；没有历史时为 0
     * @param contextTheoryScore 上下文窗口的理论分
     * @param judged      调用了 LLM 判定（成功或失败）
     * @param judgeMs     LLM 判定耗时
     * @param followUp    最近几轮判过危机：回答末尾要继续附上热线
     */
    public record Decision(boolean crisis, Layer layer, boolean strongHit, boolean weakHit,
                           double crisisScore, double theoryScore, double contextCrisisScore, double contextTheoryScore,
                           boolean judged, long judgeMs, boolean followUp, long latencyMs) {}

    private final EmbeddingModel embeddingModel;
    private final CrisisJudge judge;
    private final List<String> strongKeywords;
    private final List<String> weakKeywords;
    private final List<String> crisisExamples;
    private final List<String> theoryExamples;
    private final double threshold;
    private final String response;
    private final String hotlineText;

    private volatile List<float[]> crisisVectors;
    private volatile List<float[]> theoryVectors;

    public CrisisDetector(EmbeddingModel embeddingModel, CrisisJudge judge, PsyQaProperties props) {
        PsyQaProperties.Crisis c = props.crisis();
        this.embeddingModel = embeddingModel;
        this.judge = judge;
        this.strongKeywords = lower(readLines(c.strongKeywordsFile()));
        this.weakKeywords = lower(readLines(c.weakKeywordsFile()));
        this.crisisExamples = readLines(c.examplesFile());
        this.theoryExamples = readLines(c.theoryExamplesFile());
        this.threshold = c.semanticThreshold();
        this.hotlineText = c.hotlineText();
        this.response = """
                听起来你现在可能正经历很艰难的时刻，谢谢你愿意说出来。

                这个工具只能讨论心理学经典著作里的理论，没有办法给你真正需要的支持。请联系能帮助你的人：

                - %s
                - 也可以告诉一位你信任的家人或朋友，你不必一个人扛着。
                """.formatted(c.hotlineText());
    }

    public Decision detect(String question) {
        return detect(question, List.of());
    }

    public Decision detect(String question, List<Turn> history) {
        long t0 = System.nanoTime();
        boolean followUp = recentCrisis(history);
        String q = question.toLowerCase(Locale.ROOT);
        boolean strong = strongKeywords.stream().anyMatch(q::contains);
        boolean weak = weakKeywords.stream().anyMatch(q::contains);
        if (strong) {
            return new Decision(true, Layer.STRONG_KEYWORD, true, weak, 0, 0, 0, 0, false, 0, followUp, elapsedMs(t0));
        }
        List<String> messages = userMessages(history, question);
        double crisisScore, theoryScore, ctxCrisis = 0, ctxTheory = 0;
        try {
            ensureExampleVectors();
            float[] v;
            float[] w = null;
            if (messages.size() == 1) {
                v = embeddingModel.embed(question);
            } else {
                List<float[]> vs = embeddingModel.embed(List.of(question, String.join("\n", messages)));
                v = vs.get(0);
                w = vs.get(1);
            }
            crisisScore = maxCosine(v, crisisVectors);
            theoryScore = maxCosine(v, theoryVectors);
            if (w != null) {
                ctxCrisis = maxCosine(w, crisisVectors);
                ctxTheory = maxCosine(w, theoryVectors);
            }
        } catch (RuntimeException e) {
            // 语义层失败时不拦截：强关键词已经查过；这时检索多半也会失败，前端收到 error 事件。
            // 如果改成"失败就显示求助信息"，百炼一出故障所有用户都会看到热线号码
            log.error("危机语义层失败，按非危机继续（强关键词已检查）", e);
            return new Decision(false, Layer.SEMANTIC_ERROR, false, weak, 0, 0, 0, 0, false, 0, followUp, elapsedMs(t0));
        }
        if (!(crisisScore > theoryScore || ctxCrisis > ctxTheory)) {
            return new Decision(false, Layer.NONE, false, weak, crisisScore, theoryScore, ctxCrisis, ctxTheory,
                    false, 0, followUp, elapsedMs(t0));
        }
        long j0 = System.nanoTime();
        try {
            boolean risk = judge.judge(messages).risk();
            return new Decision(risk, risk ? Layer.JUDGE : Layer.JUDGE_SAFE, false, weak, crisisScore, theoryScore,
                    ctxCrisis, ctxTheory, true, elapsedMs(j0), followUp, elapsedMs(t0));
        } catch (RuntimeException e) {
            boolean crisis = decide(false, weak, crisisScore, theoryScore, threshold);
            log.warn("危机 LLM 判定失败，按语义层规则判定（crisis={}）：{}", crisis, e.getMessage());
            return new Decision(crisis, crisis ? Layer.SEMANTIC : Layer.NONE, false, weak, crisisScore, theoryScore,
                    ctxCrisis, ctxTheory, true, elapsedMs(j0), followUp, elapsedMs(t0));
        }
    }

    /** 最近 CONTEXT_TURNS 轮用户原话 + 这一句，按时间顺序。只取用户的话：助手的回答是大段理论或热线文字，会把分数带偏。 */
    static List<String> userMessages(List<Turn> history, String question) {
        List<String> out = new ArrayList<>();
        if (history != null) {
            for (Turn t : history.subList(Math.max(0, history.size() - CONTEXT_TURNS), history.size())) {
                out.add(t.question().strip());
            }
        }
        out.add(question.strip());
        return out;
    }

    static boolean recentCrisis(List<Turn> history) {
        if (history == null) {
            return false;
        }
        return history.subList(Math.max(0, history.size() - FOLLOW_UP_TURNS), history.size()).stream().anyMatch(Turn::crisis);
    }

    /** 判定规则。纯函数：评估报告的阈值扫描用同一个方法，保证扫描结果和线上一致。 */
    public static boolean decide(boolean strongHit, boolean weakHit, double crisisScore, double theoryScore, double threshold) {
        if (strongHit) {
            return true;
        }
        boolean closerToCrisis = crisisScore > theoryScore;
        return weakHit ? closerToCrisis : closerToCrisis && crisisScore >= threshold;
    }

    /** Web 模式启动后预热例句向量。失败只记日志，第一次请求时会再试。 */
    @EventListener
    public void warmUp(ApplicationReadyEvent event) {
        if (event.getSpringApplication().getWebApplicationType() == WebApplicationType.NONE) {
            return;
        }
        try {
            ensureExampleVectors();
        } catch (RuntimeException e) {
            log.error("危机语义层预热失败，第一次请求时再试", e);
        }
    }

    public double threshold() {
        return threshold;
    }

    public String response() {
        return response;
    }

    /** followUp 时附在回答末尾的一句。 */
    public String followUpNote() {
        return "如果你还是觉得难受，随时可以联系：" + hotlineText;
    }

    private void ensureExampleVectors() {
        if (crisisVectors == null) {
            synchronized (this) {
                if (crisisVectors == null) {
                    theoryVectors = embedAll(theoryExamples);
                    crisisVectors = embedAll(crisisExamples);
                    log.info("危机语义层例句向量已缓存：危机 {} 条，理论 {} 条", crisisVectors.size(), theoryVectors.size());
                }
            }
        }
    }

    private List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBED_BATCH) {
            out.addAll(embeddingModel.embed(texts.subList(from, Math.min(from + EMBED_BATCH, texts.size()))));
        }
        return List.copyOf(out);
    }

    static double maxCosine(float[] v, List<float[]> others) {
        double best = 0;
        for (float[] o : others) {
            best = Math.max(best, cosine(v, o));
        }
        return best;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static List<String> readLines(Resource r) {
        try {
            return r.getContentAsString(StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取危机识别配置失败：" + r, e);
        }
    }

    private static List<String> lower(List<String> l) {
        return l.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
