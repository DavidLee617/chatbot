# PsyQA 多轮检索评估 2026-10-19 03:05

| 配置 | 值 |
|---|---|
| 题库 | eval/conversations-heldout.jsonl |
| top-k | 5 |
| 结合历史改写 | 是 |
| 查询方式 | BOTH |

```
追问 10 轮：recall@1 40.0%  recall@5 90.0%  MRR 0.587
第 1 轮（参照）9 轮：recall@5 77.8%
改写 / 翻译开销：18 轮，平均 1690 ms，共 5033 tokens
```

| 对话 | 轮 | 问题 | 实际检索的英文 | 首个命中 | top-1 | top-1 来源 |
|---|---|---|---|---|---|---|
| h01 | 1 | 弗洛伊德说的快乐原则是什么？ | What is the pleasure principle according to Freud? | 1 | 0.55 | A General Introduction to Psychoanalysis · TWENTY-SECOND LECTURE · GENERAL THEORY OF THE NEUROSES |
| h01 | 2 | 后面那个原则是怎么发展出来的？ | How does the reality principle develop? | 2 | 0.50 | Psychology of the Unconscious · Front Matter |
| h02 | 1 | 弗洛伊德怎么划分儿童性欲的发展？ | How does Freud divide the development of children's sexuality? | 未命中 ❌ | 0.60 | Psychology of the Unconscious · Front Matter |
| h02 | 2 | 中间那段平静的时期叫什么，为什么会出现？ | What is the relatively calm period in the middle of Freud's stages of psychosexual development called, and why does it occur? | 3 | 0.54 | The Theory of Psychoanalysis · CHAPTER III The Conception of Libido |
| h03 | 1 | 荣格和弗洛伊德对梦的看法有什么不同？ | What are the differences between Jung's and Freud's views on dreams? | 未命中 ❌ | 0.56 | Collected Papers on Analytical Psychology · CHAPTER XII · THE PSYCHOLOGY OF DREAMS |
| h03 | 2 | 后一种看法具体是什么意思？ | What does Jung's view that dreams have a future-oriented meaning and point out a direction for the dreamer specifically mean? | 5 | 0.61 | Collected Papers on Analytical Psychology · CHAPTER VII · PSYCHOANALYSIS |
| h04 | 1 | 荣格怎么分析英雄神话里的出生故事？ | How does Jung analyze the birth story in hero myths? | 1 | 0.65 | Psychology of the Unconscious · CHAPTER VII THE DUAL MOTHER RÔLE |
| h04 | 2 | 这种现象他叫什么，又是怎么解释的？ | - | 未命中 ❌ | 0.38 | A General Introduction to Psychoanalysis · SECOND LECTURE · THE PSYCHOLOGY OF ERRORS |
| h05 | 1 | 弗洛伊德怎么看白日梦？ | How does Freud view daydreams? | 1 | 0.67 | A General Introduction to Psychoanalysis · FIFTH LECTURE · THE DREAM |
| h05 | 2 | 这种幻想一般从什么年纪开始出现？ | At what age do the fantasies of daydreams generally begin to appear? | 1 | 0.57 | A General Introduction to Psychoanalysis · FIFTH LECTURE · THE DREAM |
| h05 | 3 | 那这两者最大的区别是什么？ | What is the biggest difference between daydreams and night dreams? | 1 | 0.46 | A General Introduction to Psychoanalysis · EIGHTH LECTURE · THE DREAM |
| h06 | 1 | 弗洛伊德说梦的工作有哪几种手段？ | What methods does Freud say dream-work has? | 3 | 0.70 | A General Introduction to Psychoanalysis · ELEVENTH LECTURE · THE DREAM |
| h06 | 2 | 最后那一种是怎么起作用的？ | How does the last of the four dream-work methods, secondary revision, function? | 1 | 0.53 | The Interpretation of Dreams · VII THE PSYCHOLOGY OF THE DREAM ACTIVITIES |
| h07 | 1 | 弗洛伊德说力比多的发展会在哪些地方出问题？ | Where does Freud say problems can arise in the development of libido? | 4 | 0.60 | Psychology of the Unconscious · CHAPTER II THE CONCEPTION AND THE GENETIC THEORY OF LIBIDO |
| h07 | 2 | 这两种危险之间是什么关系？ | What is the relationship between fixation and regression as the two dangers in Freud's theory of libido development? | 3 | 0.66 | A General Introduction to Psychoanalysis · TWENTY-THIRD LECTURE · GENERAL THEORY OF THE NEUROSES |
| h08 | 1 | 弗洛伊德说的移情是什么？ | What is transference according to Freud? | 1 | 0.60 | A General Introduction to Psychoanalysis · TWENTY-SEVENTH LECTURE · GENERAL THEORY OF THE NEUROSES |
| h08 | 2 | 带敌意的那种对治疗有什么影响？ | What effect does hostile transference have on treatment? | 1 | 0.60 | A General Introduction to Psychoanalysis · TWENTY-SEVENTH LECTURE · GENERAL THEORY OF THE NEUROSES |
| h09 | 1 | 荣格怎么看神经症的成因？ | How does Jung view the causes of neurosis? | 4 | 0.65 | Psychology of the Unconscious · Front Matter |
| h09 | 2 | 为什么他更看重前面那个因素？ | Why did Jung place more importance on the patient's current conflicts and adjustment difficulties than on childhood trauma as the cause of neurosis? | 2 | 0.64 | Collected Papers on Analytical Psychology · CHAPTER XIV · THE PSYCHOLOGY OF THE UNCONSCIOUS PROCESSES |
