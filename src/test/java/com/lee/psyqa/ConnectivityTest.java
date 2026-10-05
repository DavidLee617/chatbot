package com.lee.psyqa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1：连通百炼（embedding + chat）和 Milvus（上下文启动时会建 collection）。
 * 需要外部服务，默认不跑：mvn test -Dgroups=integration
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConnectivityTest {

    @Autowired EmbeddingModel embeddingModel;
    @Autowired ChatClient.Builder chatClientBuilder;

    @Test
    void embedReturns1024Dims() {
        float[] v = embeddingModel.embed("荣格说的集体无意识是什么？");
        assertThat(v).hasSize(1024);
    }

    @Test
    void chatReturnsText() {
        String answer = chatClientBuilder.build().prompt().user("用一句话回答：1+1=?").call().content();
        assertThat(answer).isNotBlank();
    }
}
