package com.lee.psyqa.answer;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 把检索块编号 [1]..[n] 放进 &lt;context&gt;，套进 prompts/answer.st。
 * 用简单的字符串替换而不是 StringTemplate：原文里的花括号、尖括号不会被当成模板语法。
 */
@Component
public class PromptBuilder {

    private final String template;

    public PromptBuilder(@Value("classpath:prompts/answer.st") Resource template) throws IOException {
        this.template = template.getContentAsString(StandardCharsets.UTF_8);
    }

    public String build(String question, List<Document> docs) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            var m = d.getMetadata();
            if (i > 0) {
                ctx.append("\n\n");
            }
            ctx.append("[%d] 《%s》%s，%s%n".formatted(i + 1, m.get("book"), m.get("author"), m.get("chapter")))
                    // 防止原文伪造边界标签，跳出数据区
                    .append(d.getText().replace("<context>", "").replace("</context>", ""));
        }
        return template.replace("{context}", ctx).replace("{question}", question);
    }
}
