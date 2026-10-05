# PsyQA 交接文档

> 截至 2026-10-04。本文件是新会话的起点：读完它和第 1 节列出的文件，就能接手当前进度。

---

## 0. 给新会话的工作约定

- **一律用中文回答**，包括总结和进度说明。代码标识符、命令、文件名保持原样。
- 用户有 C# / .NET 背景。代码注释里的「C# 对照」/「≈ …」是给用户看的，新写的代码保持这个风格。
- `docs/DESIGN.md` 是需求来源（设计文档 v1.0 原文）：按里程碑实现，不加设计没要求的功能；文档与实际环境冲突时先提出来。和它不一致的实现决定记在本文件第 4 节。
- **密钥**：`DASHSCOPE_API_KEY` 只从环境变量读，`application.yml` 里没有任何密钥。不要打印、不要写进文件。
- **本机有 HTTP 代理**（`HTTP_PROXY` 等环境变量）。curl 访问 localhost / Milvus 必须加 `--noproxy '*'`，否则拿到 502 或空响应。Java 不读这些环境变量，直连。
- **zsh 不对变量做分词**：`for p in "a 1"; do set -- $p` 和 `C="curl -s"; $C ...` 都会出错（今天踩了两次），写 shell 时用函数或数组。
- **Milvus 里还有一个 `biz` collection 不属于本项目**，不要动。不要停 Milvus 容器。
- **git**：提交信息结尾加 `Co-Authored-By` 行。推送、合并之前先问用户。

---

## 1. 新会话要读的文件（按顺序）

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `docs/DESIGN.md` | 范围、护栏、SSE 协议、里程碑（第 12 节）、第二阶段（第 13 节） |
| 2 | `src/main/resources/application.yml` | 模型 ID、关 thinking 的 `extra-body`、Milvus 配置、`psyqa.*` 参数 |
| 3 | `src/main/java/com/lee/psyqa/answer/AnswerService.java` | 问答主流程，从这里往下读最快 |
| 4 | `src/main/java/com/lee/psyqa/ingest/` | 清洗 → 分章 → 切块 → 写入 |
| 5 | `src/main/resources/prompts/answer.st` | 回答 prompt（5 条规则） |

---

## 2. 项目是什么

Java 版 RAG 问答。知识库是荣格、弗洛伊德的 5 本 Gutenberg 公版英文书；用户用中文提问，系统检索英文原文，用中文回答并用 [n] 标注出处。

技术栈：Spring Boot 3.5.16 + Spring AI 1.1.8，JDK 21，Maven（版本与 pr-reviewer 一致）。百炼走 OpenAI 兼容接口；向量库是本机 Docker 里的 Milvus 2.5.10。

**本地 vs 远程的分工**（用户问过，回答时保持一致）：

| 步骤 | 在哪做 |
|---|---|
| 清洗、分章、切块 | 本地 Java，5 本书不到 1 秒 |
| 文字 → 1024 维向量 | 百炼 `text-embedding-v4`（远程 HTTPS，每批 ≤ 10 条） |
| 存向量、相似度检索 | 本地 Milvus（gRPC 19530） |
| 危机词、阈值过滤、拼 prompt | 本地 Java |
| 生成中文回答 | 百炼 `deepseek-v4.1-flash` |

ingest 是**串行**的：先把一本书全部切完放在内存里，再每 10 块调一次 embedding、写一次 Milvus。切好的块不落盘，只存在 Milvus 里。

---

## 3. 里程碑进度

| # | 内容 | 状态 | 验证方式 |
|---|---|---|---|
| M1 | 骨架，连通百炼和 Milvus | ✅ | `mvn test -Pintegration`（2 个测试） |
| M2 | 清洗 + 切块 | ✅ | `IngestPipelineTest`，含在 5 本真书上的检查；人工抽查过每本的章节列表 |
| M3 | ingest | ✅ | 5 本共 2,977 块，失败 0；重导 Theory of Psychoanalysis 后总数仍是 2,977 |
| M4 | ask CLI + 三道护栏 | ✅ | 正常问题有 [1]–[5] 引用；"今天天气怎么样"最高分 0.19 被拒答；"不想活了"走 CRISIS，0 ms，不检索不调模型 |
| M5 | Web 流式页面 | ⚠️ 基本完成 | curl 确认事件顺序 route → sources → token×90 → done；**浏览器里还没人看过** |

各书块数：Collected Papers 691、Psychology of the Unconscious 573、Theory of Psychoanalysis 221、General Introduction 683、Interpretation of Dreams 809。

---

## 4. 和设计文档不一致的决定

| 决定 | 原因 |
|---|---|
| chunkId = sha1(book\|chapter\|index) 截成 UUID 格式 | Spring AI 的 Milvus doc_id 字段是 VarChar(36)，40 位 sha1 hex 放不下。仍是确定性的 |
| chunkIndex 是全书内的序号，不是章内序号 | Collected Papers 等书里同名章节会出现两次（如两个 Part 各有 CHAPTER I），章内序号会撞 id |
| 每本书导入前按 `book` 元数据删旧数据 | Milvus insert 不按主键覆盖，不删会重复 |
| 章名带上标题，如 `CHAPTER III · THE HYMN OF CREATION` | 引用时更可读 |
| 清洗额外去掉 `[Footnote …]`、行内脚注号 `[12]`/`[D]`、`_斜体_` 下划线；截掉书尾 INDEX / NOTES / LITERARY INDEX | 不截的话 Psychology of the Unconscious 的尾注会多出一整轮假章节，Dreams 会多出 742 段索引 |
| 章名以页码结尾的整章丢弃 | Collected Papers 的目录混有小写摘要，目录启发式删不干净 |
| `extra-body: enable_thinking: false` | deepseek-v4.1-flash 默认开 thinking，白费延迟和 token |
| `stream-usage: true` | 流式调用才能拿到 token 用量写日志 |
| `spring.ai.retry.max-attempts: 1` | ingest 自己有批次重试（2 次，1s/2s 退避），避免两层叠加 |
| 连续 3 批失败就中止 ingest | 今天遇到百炼欠费（`Arrearage`），不中止会白跑 300 批 × 3 次 |
| SSE 的 data 一律 JSON 编码（token 也是） | SSE 会吞掉 data 行首空格，换行也要转义 |
| 检索阶段异常也发 `error` + `done` 事件 | 否则前端拿到裸 HTTP 500 |
| prompt 加第 5 条：不要提到 "context""资料" | 第一次回答开头出现"根据 <context> 的内容" |
| 集成测试用 `@Tag("integration")` + Maven profile | 默认 `mvn test` 不需要网络 |

---

## 5. 常用命令

```bash
mvn test                                   # 12 个单元测试，不需要外部服务
mvn test -Pintegration                     # 连百炼 + Milvus 的集成测试
mvn -DskipTests package                    # 产出 target/psyqa.jar

java -jar target/psyqa.jar ingest [--book jung-theory-psychoanalysis.txt]
java -jar target/psyqa.jar ask "荣格说的集体无意识是什么？"
java -jar target/psyqa.jar                 # Web，http://localhost:8080

# 长任务用 nohup，会话断了也不受影响
nohup java -jar target/psyqa.jar ingest > logs/ingest.log 2>&1 &
tail -f logs/ingest.log | tr '\r' '\n'

# Milvus 里的块数（注意 --noproxy）
curl -s --noproxy '*' -X POST localhost:19530/v2/vectordb/entities/query \
  -H 'Content-Type: application/json' \
  -d '{"collectionName":"psyqa_chunks","filter":"","outputFields":["count(*)"]}'
```

Attu（Milvus 管理界面）在 http://localhost:8000，地址填 `host.docker.internal:19530`。填 `standalone:19530` 会报 "Name resolution failed"：Attu 和 Milvus 不在同一个 Docker 网络里。

---

## 6. 待办

**v1 收尾**
1. 浏览器里打开 http://localhost:8080 看 M5：来源卡片先出现、回答逐字显示、CRISIS 样式、手机宽度
2. 确认 prompt 第 5 条生效（回答不再出现"根据 <context>"）
3. 上线前核对热线号码（`psyqa.crisis.hotline-text`）

**观察到、还没处理的**
- 块开头常是半句话：重叠是"上一块末尾 200 字符，从词边界开始"。可以改成从句子边界开始
- "集体无意识"那题的 5 个来源全是 Collected Papers 第 XIV、XV 章，分数挤在 0.55–0.56；Psychology of the Unconscious 没被召回。需要评估集量化
- Collected Papers 的 CHAPTER II（The Association Method）没有正文，直接是 LECTURE I，所以 LECTURE I 的章名里没有"Association Method"
- ingest 串行，全量约 10 分钟；可加可配置并发（如 4）
- CLI 的 `ask` 会打一条 `Removing streamOptions…` WARN（`stream-usage` 对非流式调用无效），无害

**第二阶段**（设计文档第 13 节）
- `eval/questions.jsonl` 20–30 题，标注应命中的书 + 章，算 recall@5；先冻结测试集再调参
- 用评估数据调 `similarity-threshold`（目前相关题 0.55–0.61，无关题 0.16–0.19）和 `chunk-size`
- 升级候选：查询改写（中→英再检索）、rerank、混合检索、多轮记忆
