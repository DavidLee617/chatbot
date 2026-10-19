package com.lee.psyqa.memory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * user_memory 表的读写（≈ Dapper：手写 SQL + 行映射，不上 ORM）。表结构见 schema.sql。
 * 每个用户的记忆很少（上限 {@link MemoryService#MAX_PER_USER} 条），一次全部读出来在 Java 里算相似度。
 */
@Repository
public class MemoryRepository {

    private final JdbcClient jdbc;

    public MemoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Memory> findByUser(String userId) {
        return jdbc.sql("SELECT * FROM user_memory WHERE user_id = :u ORDER BY updated_at DESC")
                .param("u", userId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public void insert(Memory m) {
        jdbc.sql("""
                        INSERT INTO user_memory (id, user_id, kind, content, embedding, created_at, updated_at)
                        VALUES (:id, :u, :k, :c, CAST(:e AS real[]), :t, :t)""")
                .param("id", m.id()).param("u", m.userId()).param("k", m.kind().name()).param("c", m.content())
                .param("e", literal(m.embedding())).param("t", Timestamp.from(Instant.now()))
                .update();
    }

    public void update(UUID id, String content, float[] embedding) {
        jdbc.sql("UPDATE user_memory SET content = :c, embedding = CAST(:e AS real[]), updated_at = now() WHERE id = :id")
                .param("c", content).param("e", literal(embedding)).param("id", id)
                .update();
    }

    /** 主题又聊到了：只刷新时间（召回和清理都看 updated_at）。 */
    public void touch(UUID id) {
        jdbc.sql("UPDATE user_memory SET updated_at = now() WHERE id = :id").param("id", id).update();
    }

    /** 带 user_id 条件：只能删自己的。 */
    public boolean delete(String userId, UUID id) {
        return jdbc.sql("DELETE FROM user_memory WHERE id = :id AND user_id = :u")
                .param("id", id).param("u", userId).update() > 0;
    }

    public int deleteAll(String userId) {
        return jdbc.sql("DELETE FROM user_memory WHERE user_id = :u").param("u", userId).update();
    }

    private static Memory map(ResultSet rs) throws SQLException {
        return new Memory(rs.getObject("id", UUID.class), rs.getString("user_id"),
                Memory.Kind.valueOf(rs.getString("kind")), rs.getString("content"), toFloats(rs.getArray("embedding")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 向量按 PostgreSQL 数组字面量 "{0.1,0.2,…}" 传，SQL 里 CAST 成 real[]。
     * 不直接传 Java 数组：命名参数模板会把数组 / 集合展开成 IN 列表。
     */
    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('{');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append('}').toString();
    }

    private static float[] toFloats(Array a) throws SQLException {
        Object[] arr = (Object[]) a.getArray();
        float[] out = new float[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = ((Number) arr[i]).floatValue();
        }
        return out;
    }
}
