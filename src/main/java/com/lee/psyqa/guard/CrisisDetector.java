package com.lee.psyqa.guard;

import com.lee.psyqa.config.PsyQaProperties;
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
 * 危机识别，排在检索之前，和知识库无关。两层：
 * <ol>
 *   <li><b>强关键词</b>（第一人称意图，如"想自杀""kill myself"）：命中直接判危机，不看语义。安全底线，零成本、确定</li>
 *   <li><b>语义层</b>：问题向量分别和"危机例句""理论讨论例句"算最大余弦相似度，判断问题更像哪一类：
 *     <ul>
 *       <li>命中弱关键词（话题词，如"自杀"）：话题已经确定，只看更像哪一类（危机分 &gt; 理论分）</li>
 *       <li>没命中关键词：危机分 ≥ 阈值，并且危机分 &gt; 理论分</li>
 *     </ul>
 *   </li>
 * </ol>
 * 比两类而不是只比危机例句：向量相似度更擅长判断"话题像不像"，"弗洛伊德怎么看自杀"和"我想自杀"话题一样，
 * 要靠"更像哪一类"区分意图。
 * <p>
 * 语义层要多一次 embedding 调用：VectorStore.similaritySearch 只接受文本、内部自己算向量，拿不到它算好的问题向量。
 * 例句向量在第一次用到时才算（每批 ≤ 10 条，约 3 秒）并缓存，ingest 等不用它的命令不会白花这几次调用；
 * Web 模式启动完成后预热一次，第一个用户不用等。
 * <p>
 * AnswerService 和 EvalService 都调用 {@link #detect}，保证评估测的是真实路径。
 */
@Component
public class CrisisDetector {

    private static final Logger log = LoggerFactory.getLogger(CrisisDetector.class);
    private static final int EMBED_BATCH = 10;   // text-embedding-v4 单次最多 10 条

    /** 判定来自哪一层，写进日志和评估结果。 */
    public enum Layer { STRONG_KEYWORD, SEMANTIC, NONE, SEMANTIC_ERROR }

    /**
     * @param crisisScore 和危机例句的最大相似度；强关键词命中或语义层出错时为 0
     * @param theoryScore 和理论讨论例句的最大相似度
     */
    public record Decision(boolean crisis, Layer layer, boolean strongHit, boolean weakHit,
                           double crisisScore, double theoryScore, long latencyMs) {}

    private final EmbeddingModel embeddingModel;
    private final List<String> strongKeywords;
    private final List<String> weakKeywords;
    private final List<String> crisisExamples;
    private final List<String> theoryExamples;
    private final double threshold;
    private final String response;

    private volatile List<float[]> crisisVectors;
    private volatile List<float[]> theoryVectors;

    public CrisisDetector(EmbeddingModel embeddingModel, PsyQaProperties props) {
        PsyQaProperties.Crisis c = props.crisis();
        this.embeddingModel = embeddingModel;
        this.strongKeywords = lower(readLines(c.strongKeywordsFile()));
        this.weakKeywords = lower(readLines(c.weakKeywordsFile()));
        this.crisisExamples = readLines(c.examplesFile());
        this.theoryExamples = readLines(c.theoryExamplesFile());
        this.threshold = c.semanticThreshold();
        this.response = """
                听起来你现在可能正经历很艰难的时刻，谢谢你愿意说出来。

                这个工具只能讨论心理学经典著作里的理论，没有办法给你真正需要的支持。请联系能帮助你的人：

                - %s
                - 也可以告诉一位你信任的家人或朋友，你不必一个人扛着。
                """.formatted(c.hotlineText());
    }

    public Decision detect(String question) {
        long t0 = System.nanoTime();
        String q = question.toLowerCase(Locale.ROOT);
        boolean strong = strongKeywords.stream().anyMatch(q::contains);
        boolean weak = weakKeywords.stream().anyMatch(q::contains);
        if (strong) {
            return new Decision(true, Layer.STRONG_KEYWORD, true, weak, 0, 0, elapsedMs(t0));
        }
        try {
            ensureExampleVectors();
            float[] v = embeddingModel.embed(question);
            double crisisScore = maxCosine(v, crisisVectors);
            double theoryScore = maxCosine(v, theoryVectors);
            boolean crisis = decide(false, weak, crisisScore, theoryScore, threshold);
            return new Decision(crisis, crisis ? Layer.SEMANTIC : Layer.NONE, false, weak, crisisScore, theoryScore, elapsedMs(t0));
        } catch (RuntimeException e) {
            // 语义层失败时不拦截：强关键词已经查过；这时检索多半也会失败，前端收到 error 事件。
            // 如果改成"失败就显示求助信息"，百炼一出故障所有用户都会看到热线号码
            log.error("危机语义层失败，按非危机继续（强关键词已检查）", e);
            return new Decision(false, Layer.SEMANTIC_ERROR, false, weak, 0, 0, elapsedMs(t0));
        }
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
