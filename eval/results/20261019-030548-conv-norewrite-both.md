# PsyQA 多轮检索评估 2026-10-19 03:05

| 配置 | 值 |
|---|---|
| 题库 | eval/conversations-heldout.jsonl |
| top-k | 5 |
| 结合历史改写 | 否（对照组） |
| 查询方式 | BOTH |

```
追问 10 轮：recall@1 10.0%  recall@5 20.0%  MRR 0.133
第 1 轮（参照）9 轮：recall@5 77.8%
改写 / 翻译开销：19 轮，平均 1240 ms，共 3648 tokens
```

| 对话 | 轮 | 问题 | 实际检索的英文 | 首个命中 | top-1 | top-1 来源 |
|---|---|---|---|---|---|---|
| h01 | 1 | 弗洛伊德说的快乐原则是什么？ | What is the pleasure principle according to Freud? | 1 | 0.55 | A General Introduction to Psychoanalysis · TWENTY-SECOND LECTURE · GENERAL THEORY OF THE NEUROSES |
| h01 | 2 | 后面那个原则是怎么发展出来的？ | How did that latter principle develop? | 3 | 0.40 | A General Introduction to Psychoanalysis · TWENTY-SECOND LECTURE · GENERAL THEORY OF THE NEUROSES |
| h02 | 1 | 弗洛伊德怎么划分儿童性欲的发展？ | How does Freud divide the development of children's sexuality? | 未命中 ❌ | 0.60 | Psychology of the Unconscious · Front Matter |
| h02 | 2 | 中间那段平静的时期叫什么，为什么会出现？ | What is that calm period in the middle called, and why does it occur? | 未命中 ❌ | 0.36 | The Theory of Psychoanalysis · CHAPTER III The Conception of Libido |
| h03 | 1 | 荣格和弗洛伊德对梦的看法有什么不同？ | What are the differences between Jung's and Freud's views on dreams? | 未命中 ❌ | 0.56 | Collected Papers on Analytical Psychology · CHAPTER XII · THE PSYCHOLOGY OF DREAMS |
| h03 | 2 | 后一种看法具体是什么意思？ | What does the latter view specifically mean? | 未命中 ❌ | 0.37 | Collected Papers on Analytical Psychology · Front Matter |
| h04 | 1 | 荣格怎么分析英雄神话里的出生故事？ | How does Jung analyze the birth story in hero myths? | 1 | 0.65 | Psychology of the Unconscious · CHAPTER VII THE DUAL MOTHER RÔLE |
| h04 | 2 | 这种现象他叫什么，又是怎么解释的？ | What does he call this phenomenon, and how does he explain it? | 未命中 ❌ | 0.40 | A General Introduction to Psychoanalysis · SECOND LECTURE · THE PSYCHOLOGY OF ERRORS |
| h05 | 1 | 弗洛伊德怎么看白日梦？ | How does Freud view daydreams? | 1 | 0.67 | A General Introduction to Psychoanalysis · FIFTH LECTURE · THE DREAM |
| h05 | 2 | 这种幻想一般从什么年纪开始出现？ | At what age do such fantasies generally begin to appear? | 未命中 ❌ | 0.51 | A General Introduction to Psychoanalysis · TWENTY-THIRD LECTURE · GENERAL THEORY OF THE NEUROSES |
| h05 | 3 | 那这两者最大的区别是什么？ | What is the biggest difference between the two? | 未命中 ❌ | 0.27 | Collected Papers on Analytical Psychology · LECTURE II · THE FAMILIAL CONSTELLATIONS |
| h06 | 1 | 弗洛伊德说梦的工作有哪几种手段？ | What methods does Freud say the dream-work has? | 2 | 0.70 | A General Introduction to Psychoanalysis · ELEVENTH LECTURE · THE DREAM |
| h06 | 2 | 最后那一种是怎么起作用的？ | How does the last one work? | 未命中 ❌ | 0.40 | Collected Papers on Analytical Psychology · Front Matter |
| h07 | 1 | 弗洛伊德说力比多的发展会在哪些地方出问题？ | Where does Freud say problems can arise in the development of libido? | 4 | 0.60 | Psychology of the Unconscious · CHAPTER II THE CONCEPTION AND THE GENETIC THEORY OF LIBIDO |
| h07 | 2 | 这两种危险之间是什么关系？ | What is the relationship between these two dangers? | 未命中 ❌ | 0.39 | A General Introduction to Psychoanalysis · TWENTY-FIFTH LECTURE · GENERAL THEORY OF THE NEUROSES |
| h08 | 1 | 弗洛伊德说的移情是什么？ | What is transference according to Freud? | 1 | 0.60 | A General Introduction to Psychoanalysis · TWENTY-SEVENTH LECTURE · GENERAL THEORY OF THE NEUROSES |
| h08 | 2 | 带敌意的那种对治疗有什么影响？ | What impact does that kind of hostility have on treatment? | 1 | 0.46 | A General Introduction to Psychoanalysis · TWENTY-SEVENTH LECTURE · GENERAL THEORY OF THE NEUROSES |
| h09 | 1 | 荣格怎么看神经症的成因？ | How does Jung view the causes of neurosis? | 4 | 0.65 | Psychology of the Unconscious · Front Matter |
| h09 | 2 | 为什么他更看重前面那个因素？ | Why does he value the former factor more? | 未命中 ❌ | 0.42 | Collected Papers on Analytical Psychology · CHAPTER IX · ON SOME CRUCIAL POINTS IN PSYCHOANALYSIS |
