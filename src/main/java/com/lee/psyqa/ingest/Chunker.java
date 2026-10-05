package com.lee.psyqa.ingest;

import com.lee.psyqa.config.PsyQaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 章内按段落累积切块：累积到 chunkSize 就切出一块，下一块以上一块末尾 overlap 个字符开头。块不跨章。
 * 单段超过 (chunkSize - overlap) 时先按句子切开，保证加上重叠后每块仍不超过 chunkSize。
 */
@Component
public class Chunker {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;:])\\s+");
    /** 没有小写字母的短段落是标题、题名页之类，没有检索价值。 */
    private static final int HEADING_MAX_CHARS = 200;

    private final int chunkSize;
    private final int overlap;

    @Autowired   // 有两个构造器时告诉 Spring 用这个；另一个给单元测试用
    public Chunker(PsyQaProperties props) {
        this(props.ingest().chunkSizeChars(), props.ingest().chunkOverlapChars());
    }

    Chunker(int chunkSize, int overlap) {
        if (overlap * 2 >= chunkSize) {
            throw new IllegalArgumentException("chunk-overlap-chars 必须小于 chunk-size-chars 的一半");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<Chunk> chunk(BookMeta book, List<Chapter> chapters) {
        List<Chunk> out = new ArrayList<>();
        for (Chapter chapter : chapters) {
            for (String text : chunkChapter(chapter.paragraphs())) {
                int index = out.size();
                out.add(new Chunk(chunkId(book.title(), chapter.title(), index), book.title(), book.author(),
                        chapter.title(), index, book.sourceUrl(), text));
            }
        }
        return out;
    }

    List<String> chunkChapter(List<String> paragraphs) {
        List<String> units = new ArrayList<>();
        for (String p : paragraphs) {
            if (p.length() <= HEADING_MAX_CHARS && !GutenbergCleaner.hasLowercase(p)) {
                continue;
            }
            // 留出重叠部分和 "\n\n" 分隔符的位置
            units.addAll(splitLong(p, chunkSize - overlap - 2));
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean curHasNewContent = false;     // 只有重叠部分、没有新段落时不输出
        for (String unit : units) {
            int sepLen = cur.isEmpty() ? 0 : 2;
            if (curHasNewContent && cur.length() + sepLen + unit.length() > chunkSize) {
                String done = cur.toString();
                chunks.add(done);
                cur = new StringBuilder(overlapTail(done));
                curHasNewContent = false;
                sepLen = cur.isEmpty() ? 0 : 2;
            }
            if (sepLen > 0) {
                cur.append("\n\n");
            }
            cur.append(unit);
            curHasNewContent = true;
        }
        if (curHasNewContent) {
            chunks.add(cur.toString());
        }
        return chunks;
    }

    /** 上一块末尾 overlap 个字符，从第一个空白之后开始，避免半个单词。 */
    String overlapTail(String text) {
        if (overlap == 0 || text.length() <= overlap) {
            return overlap == 0 ? "" : text;
        }
        String tail = text.substring(text.length() - overlap);
        int ws = indexOfWhitespace(tail);
        return ws < 0 ? tail : tail.substring(ws + 1).strip();
    }

    /** 超长段落按句子打包成不超过 max 的片段；单句仍超长时按空白硬切。 */
    static List<String> splitLong(String paragraph, int max) {
        if (paragraph.length() <= max) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : SENTENCE_END.split(paragraph)) {
            for (String s : hardSplit(sentence, max)) {
                if (!cur.isEmpty() && cur.length() + 1 + s.length() > max) {
                    pieces.add(cur.toString());
                    cur.setLength(0);
                }
                if (!cur.isEmpty()) {
                    cur.append(' ');
                }
                cur.append(s);
            }
        }
        if (!cur.isEmpty()) {
            pieces.add(cur.toString());
        }
        return pieces;
    }

    private static List<String> hardSplit(String s, int max) {
        List<String> parts = new ArrayList<>();
        while (s.length() > max) {
            int cut = s.lastIndexOf(' ', max);
            if (cut <= 0) {
                cut = max;
            }
            parts.add(s.substring(0, cut).strip());
            s = s.substring(cut).strip();
        }
        if (!s.isEmpty()) {
            parts.add(s);
        }
        return parts;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * sha1(book | chapter | index) 截成 UUID 格式：Spring AI 的 Milvus doc id 字段是 VarChar(36)，放不下 40 位 sha1 hex。
     * 仍是确定性的，重复 ingest 会覆盖同一个 id。
     */
    static String chunkId(String book, String chapter, int index) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1")
                    .digest((book + "|" + chapter + "|" + index).getBytes(StandardCharsets.UTF_8));
            ByteBuffer bb = ByteBuffer.wrap(h);
            return new UUID(bb.getLong(), bb.getLong()).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
