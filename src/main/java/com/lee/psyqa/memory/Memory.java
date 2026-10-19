package com.lee.psyqa.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条长期记忆：从对话里提炼出的要点，不是原话。
 *
 * @param embedding content 的向量，召回和去重时算余弦用
 */
public record Memory(UUID id, String userId, Kind kind, String content, float[] embedding,
                     Instant createdAt, Instant updatedAt) {

    /**
     * PREFERENCE：回答方式的偏好（"简短""引英文原文"）；BACKGROUND：用户背景（专业、职业、在读的书）；
     * TOPIC：聊过的主题（"荣格的阴影"）。情绪、健康、人际关系、风险信号不属于任何一类，不存（T8 用户决定）。
     */
    public enum Kind { PREFERENCE, BACKGROUND, TOPIC;

        public boolean isFact() {
            return this != TOPIC;
        }
    }
}
