package com.lee.psyqa.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按章切分。
 * <ul>
 *   <li>主规则：以 {@code CHAPTER III}、{@code LECTURE IV} 开头的短段落（可带标题），或 {@code FIFTH LECTURE} 这类全大写段落</li>
 *   <li>兜底：整本书一个主规则标题都没有时（如 The Interpretation of Dreams），把罗马数字开头、没有小写字母的段落当作章标题</li>
 *   <li>还识别不到：整本书视为一章</li>
 * </ul>
 * 章名 = 标题 + 紧跟的一段大写小标题，如 "CHAPTER III · THE HYMN OF CREATION"。
 * 第一章之前的内容（译者序、导言等）归入 "Front Matter"。章名以页码结尾的是目录残留，整章丢弃。
 */
@Component
public class ChapterSplitter {

    static final String FRONT_MATTER = "Front Matter";

    private static final Pattern STRONG_HEADING = Pattern.compile(
            "^((CHAPTER|LECTURE)\\s+[IVXLC\\d]+\\b.{0,150}|[A-Z]+(-[A-Z]+)?\\s+LECTURE\\b[^a-z]{0,150})$");
    private static final Pattern ROMAN_HEADING = Pattern.compile("^[IVXLC]+\\.?( [^a-z]+)?$");
    private static final int SUBTITLE_MAX_CHARS = 150;
    /** 章名以页码结尾的是没被清洗掉的目录项，如 "CHAPTER II · THE ASSOCIATION METHOD 94"。 */
    private static final Pattern TOC_TITLE = Pattern.compile("\\s\\d{1,4}$");

    public List<Chapter> split(List<String> paragraphs) {
        Pattern heading = paragraphs.stream().anyMatch(p -> STRONG_HEADING.matcher(p).matches())
                ? STRONG_HEADING
                : ROMAN_HEADING;

        List<Chapter> chapters = new ArrayList<>();
        String title = FRONT_MATTER;
        List<String> body = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            String p = paragraphs.get(i);
            if (!heading.matcher(p).matches()) {
                body.add(p);
                continue;
            }
            addIfNotEmpty(chapters, title, body);
            title = p;
            body = new ArrayList<>();
            // 紧跟的大写小标题并入章名
            if (i + 1 < paragraphs.size()) {
                String next = paragraphs.get(i + 1);
                if (next.length() <= SUBTITLE_MAX_CHARS
                        && !GutenbergCleaner.hasLowercase(next)
                        && !heading.matcher(next).matches()) {
                    title = title + " · " + next;
                    i++;
                }
            }
        }
        addIfNotEmpty(chapters, title, body);
        return chapters;
    }

    private static void addIfNotEmpty(List<Chapter> chapters, String title, List<String> body) {
        if (!body.isEmpty() && !TOC_TITLE.matcher(title).find()) {
            chapters.add(new Chapter(title, List.copyOf(body)));
        }
    }
}
