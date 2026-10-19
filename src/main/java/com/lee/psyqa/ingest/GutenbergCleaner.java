package com.lee.psyqa.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把 Gutenberg 纯文本清洗成段落列表（每段一行，原来每 ~70 字符一次的硬换行已合并）。
 */
@Component
public class GutenbergCleaner {

    private static final Pattern START = Pattern.compile("^\\*\\*\\* ?START OF TH(E|IS) PROJECT GUTENBERG EBOOK.*$", Pattern.MULTILINE);
    private static final Pattern END = Pattern.compile("^\\*\\*\\* ?END OF TH(E|IS) PROJECT GUTENBERG EBOOK", Pattern.MULTILINE);

    private static final Pattern ILLUSTRATION = Pattern.compile("\\[Illustration[^\\]]*\\]");
    private static final Pattern FOOTNOTE = Pattern.compile("\\[Footnote[^\\]]*\\]");
    private static final Pattern FOOTNOTE_REF = Pattern.compile("\\[(\\d{1,3}|[A-Z]{1,2})\\]");   // [12]、[D]
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n");

    /**
     * 书尾附录的起点：索引、尾注（可带罗马数字章号，如 "VIII LITERARY INDEX"）。
     * 只在按字符数计的后 40% 里找，避免误伤正文里同名的小标题（索引条目段落很多，按段落数算会偏）。
     */
    private static final Pattern BACK_MATTER = Pattern.compile("^([IVXLC]+\\.? )?(LITERARY INDEX|INDEX|NOTES|FOOTNOTES)\\.?$");

    /** 以页码结尾的短段落，目录 / 索引的典型形态，如 "THE DREAM-WORK 141"。 */
    private static final Pattern ENDS_WITH_PAGE = Pattern.compile("\\s\\d{1,4}\\.?$");
    private static final int TOC_PARAGRAPH_MAX_CHARS = 300;
    private static final int TOC_MIN_RUN = 5;
    private static final int TOC_MIN_PAGE_LINES = 3;

    public List<String> clean(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = extractBody(text);
        text = ILLUSTRATION.matcher(text).replaceAll("");
        text = FOOTNOTE.matcher(text).replaceAll("");
        text = FOOTNOTE_REF.matcher(text).replaceAll("");

        List<String> paragraphs = new ArrayList<>();
        for (String block : BLANK_LINES.split(text)) {
            // Gutenberg 用 _下划线_ 表示斜体，常常跨行甚至跨段（整段引用的梦境），成对匹配会漏；
            // 英文正文里下划线没有别的用途，直接全部去掉
            String p = block.replace("_", "").replaceAll("\\s+", " ").strip();
            if (!p.isEmpty()) {
                paragraphs.add(p);
            }
        }
        paragraphs = cutBackMatter(paragraphs);
        return removeTocRuns(paragraphs);
    }

    static String extractBody(String text) {
        var start = START.matcher(text);
        if (!start.find()) {
            throw new IllegalArgumentException("缺少 '*** START OF THE PROJECT GUTENBERG EBOOK' 标记，可能下载到的是错误页");
        }
        var end = END.matcher(text);
        int endPos = end.find(start.end()) ? end.start() : text.length();
        return text.substring(start.end(), endPos);
    }

    static List<String> cutBackMatter(List<String> paragraphs) {
        long total = paragraphs.stream().mapToLong(String::length).sum();
        long seen = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            seen += paragraphs.get(i).length();
            if (seen >= total * 0.6 && BACK_MATTER.matcher(paragraphs.get(i)).matches()) {
                return new ArrayList<>(paragraphs.subList(0, i));
            }
        }
        return paragraphs;
    }

    /**
     * 目录启发式：连续 >= 5 个"目录式"段落（短，且以页码结尾或没有小写字母），其中至少 3 个以页码结尾，整段去掉。
     */
    static List<String> removeTocRuns(List<String> paragraphs) {
        List<String> out = new ArrayList<>(paragraphs.size());
        int i = 0;
        while (i < paragraphs.size()) {
            int j = i;
            int pageLines = 0;
            int lastPageLine = -1;
            while (j < paragraphs.size() && isTocLike(paragraphs.get(j))) {
                if (ENDS_WITH_PAGE.matcher(paragraphs.get(j)).find()) {
                    pageLines++;
                    lastPageLine = j;
                }
                j++;
            }
            if (j - i >= TOC_MIN_RUN && pageLines >= TOC_MIN_PAGE_LINES) {
                // 只删到最后一个带页码的段落：紧跟在目录后面的往往是正文第一章的标题
                i = lastPageLine + 1;
            } else if (j > i) {
                out.addAll(paragraphs.subList(i, j));
                i = j;
            } else {
                out.add(paragraphs.get(i++));
            }
        }
        return out;
    }

    private static boolean isTocLike(String p) {
        return p.length() <= TOC_PARAGRAPH_MAX_CHARS
                && (ENDS_WITH_PAGE.matcher(p).find() || !hasLowercase(p));
    }

    static boolean hasLowercase(String s) {
        return s.chars().anyMatch(Character::isLowerCase);
    }
}
