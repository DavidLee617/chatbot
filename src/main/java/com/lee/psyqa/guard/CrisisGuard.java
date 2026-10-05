package com.lee.psyqa.guard;

import com.lee.psyqa.config.PsyQaProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 危机词路由：命中就直接返回求助信息，不检索、不调模型。
 * 由代码判断而不是交给模型：条件确定、零成本，也不会被提示注入绕过。
 */
@Component
public class CrisisGuard {

    private final List<String> keywords;
    private final String response;

    public CrisisGuard(PsyQaProperties props) {
        try {
            String text = props.crisis().keywordsFile().getContentAsString(StandardCharsets.UTF_8);
            this.keywords = text.lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取危机关键词表失败", e);
        }
        this.response = """
                听起来你现在可能正经历很艰难的时刻，谢谢你愿意说出来。

                这个工具只能讨论心理学经典著作里的理论，没有办法给你真正需要的支持。请联系能帮助你的人：

                - %s
                - 也可以告诉一位你信任的家人或朋友，你不必一个人扛着。
                """.formatted(props.crisis().hotlineText());
    }

    public boolean matches(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(q::contains);
    }

    public String response() {
        return response;
    }
}
