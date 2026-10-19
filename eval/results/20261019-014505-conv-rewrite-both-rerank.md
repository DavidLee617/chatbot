# PsyQA 多轮检索评估 2026-10-19 01:45

| 配置 | 值 |
|---|---|
| 题库 | eval/conversations.jsonl |
| top-k | 5 |
| 结合历史改写 | 是 |
| 查询方式 | BOTH |

```
追问 7 轮：recall@1 57.1%  recall@5 85.7%  MRR 0.714
第 1 轮（参照）7 轮：recall@5 100.0%
改写 / 翻译开销：14 轮，平均 2152 ms，共 3788 tokens
```

| 对话 | 轮 | 问题 | 实际检索的英文 | 首个命中 | top-1 | top-1 来源 |
|---|---|---|---|---|---|---|
| d01 | 1 | 荣格说的人格面具是什么？ | What is the persona that Jung talked about? | 1 | 0.49 | Collected Papers on Analytical Psychology · CHAPTER XV · THE CONCEPTION OF THE UNCONSCIOUS |
| d01 | 2 | 它对一个人的心理健康有什么影响？ | What effect does the persona have on a person's mental health? | 1 | 0.40 | Collected Papers on Analytical Psychology · CHAPTER XV · THE CONCEPTION OF THE UNCONSCIOUS |
| d02 | 1 | 弗洛伊德说的梦的稽查是什么？ | What is the dream censorship that Freud talked about? | 1 | 0.64 | A General Introduction to Psychoanalysis · NINTH LECTURE · THE DREAM |
| d02 | 2 | 那它和凝缩作用有什么关系？ | What is the relationship between Freud's dream censorship and condensation? | 1 | 0.73 | A General Introduction to Psychoanalysis · ELEVENTH LECTURE · THE DREAM |
| d03 | 1 | 荣格的字词联想实验是怎么做的？ | How was Jung's word association experiment conducted? | 3 | 0.53 | A General Introduction to Psychoanalysis · SIXTH LECTURE · THE DREAM |
| d03 | 2 | 他用这个方法查出过小偷吗？ | Did Jung use the word association experiment to detect a thief? | 2 | 0.61 | Collected Papers on Analytical Psychology · LECTURE I |
| d04 | 1 | 弗洛伊德怎么解释口误？ | How does Freud explain slips of the tongue? | 1 | 0.68 | A General Introduction to Psychoanalysis · FOURTH LECTURE |
| d04 | 2 | 那忘记别人的名字呢，也是一样的道理吗？ | Does forgetting someone's name also work the same way as Freud's explanation of slips of the tongue? | 2 | 0.57 | A General Introduction to Psychoanalysis · SIXTH LECTURE · THE DREAM |
| d05 | 1 | 荣格为什么不同意弗洛伊德对力比多的理解？ | Why did Jung disagree with Freud's understanding of libido? | 4 | 0.72 | Psychology of the Unconscious · Front Matter |
| d05 | 2 | 那他自己是怎么定义这个概念的？ | How did Jung himself define libido? | 1 | 0.73 | Psychology of the Unconscious · Front Matter |
| d06 | 1 | 什么是俄狄浦斯情结？ | What is the Oedipus complex? | 2 | 0.56 | The Theory of Psychoanalysis · CHAPTER VII The Content of the Unconscious |
| d06 | 2 | 女孩身上有对应的情况吗？荣格管它叫什么？ | Is there a corresponding situation in girls, and what does Jung call it? | 未命中 ❌ | 0.48 | The Theory of Psychoanalysis · CHAPTER VIII The Etiology of the Neuroses |
| d07 | 1 | 弗洛伊德怎么看待焦虑？ | How does Freud view anxiety? | 1 | 0.55 | A General Introduction to Psychoanalysis · TWENTY-FIFTH LECTURE · GENERAL THEORY OF THE NEUROSES |
| d07 | 2 | 后一种是怎么产生的？ | How is neurotic anxiety produced? | 1 | 0.56 | A General Introduction to Psychoanalysis · TWENTY-FIFTH LECTURE · GENERAL THEORY OF THE NEUROSES |
