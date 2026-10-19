package com.lee.psyqa.eval;

import java.util.List;
import java.util.Locale;

/**
 * 判断一个检索到的块是否"命中"：块里出现任意一个证据短语即算。
 * <ul>
 *   <li>不区分大小写，弯引号统一成直引号，连续空白压成一个空格</li>
 *   <li>按整词匹配：短语前后不能紧挨着字母。否则 moth 会匹配到 mother、persona 会匹配到 personal</li>
 * </ul>
 * 用证据短语而不是 chunkId 标注：调 chunk-size 后块会重新切、chunkId 全变，短语标注不受影响。
 */
public final class EvidenceMatcher {

    private EvidenceMatcher() {}

    public static boolean containsAny(String text, List<String> evidence) {
        if (text == null || evidence == null) {
            return false;
        }
        String t = normalize(text);
        return evidence.stream().anyMatch(e -> containsWord(t, normalize(e)));
    }

    static String normalize(String s) {
        return s.replace('’', '\'').replace('‘', '\'')
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT)
                .strip();
    }

    static boolean containsWord(String text, String phrase) {
        if (phrase.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int i = text.indexOf(phrase, from);
            if (i < 0) {
                return false;
            }
            int end = i + phrase.length();
            boolean leftOk = i == 0 || !Character.isLetter(text.charAt(i - 1));
            boolean rightOk = end == text.length() || !Character.isLetter(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = i + 1;
        }
    }
}
