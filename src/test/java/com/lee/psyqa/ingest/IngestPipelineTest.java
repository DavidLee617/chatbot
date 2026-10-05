package com.lee.psyqa.ingest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M2：清洗 + 分章 + 切块。不依赖外部服务。 */
class IngestPipelineTest {

    private final GutenbergCleaner cleaner = new GutenbergCleaner();
    private final ChapterSplitter splitter = new ChapterSplitter();
    private final Chunker chunker = new Chunker(2000, 200);

    private static final String SAMPLE = """
            The Project Gutenberg eBook of Sample
            License blah blah.

            *** START OF THE PROJECT GUTENBERG EBOOK SAMPLE ***

            CONTENTS

            CHAPTER I

            THE FIRST THING 1

            CHAPTER II

            THE SECOND THING 17

            CHAPTER III

            THE THIRD THING 30

            CHAPTER I

            THE FIRST THING

            This is the first paragraph of chapter one, hard wrapped
            at about seventy characters like _Gutenberg_ does.[1]

            [Illustration: A picture
            of something]

            Second paragraph of chapter one.

            [Footnote 1: Some reference.]

            CHAPTER II

            Chapter two body text lives here.

            *** END OF THE PROJECT GUTENBERG EBOOK SAMPLE ***

            End license text that must not appear.
            """;

    @Test
    void cleanerStripsGutenbergHeaderFooterAndNoise() {
        List<String> paras = cleaner.clean(SAMPLE);
        String all = String.join("\n", paras);

        assertThat(all).doesNotContain("License", "START OF", "END OF", "must not appear");
        assertThat(all).doesNotContain("Illustration", "Footnote", "[1]", "_Gutenberg_");
        assertThat(all).doesNotContain("CONTENTS", "THE SECOND THING 17");   // 目录被去掉
        // 段内硬换行被合并
        assertThat(paras).contains("This is the first paragraph of chapter one, hard wrapped at about seventy characters like Gutenberg does.");
    }

    @Test
    void cleanerRejectsNonGutenbergText() {
        assertThatThrownBy(() -> cleaner.clean("<html>404</html>")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitterUsesHeadingPlusSubtitle() {
        List<Chapter> chapters = splitter.split(cleaner.clean(SAMPLE));
        assertThat(chapters).extracting(Chapter::title).containsExactly("CHAPTER I · THE FIRST THING", "CHAPTER II");
        assertThat(chapters.get(1).paragraphs()).containsExactly("Chapter two body text lives here.");
    }

    @Test
    void splitterFallsBackToRomanNumeralsThenWholeBook() {
        var roman = splitter.split(List.of("Intro text.", "I", "THE FIRST", "Body one.", "II", "Body two."));
        assertThat(roman).extracting(Chapter::title).containsExactly(ChapterSplitter.FRONT_MATTER, "I · THE FIRST", "II");

        var none = splitter.split(List.of("Just some text.", "More text."));
        assertThat(none).hasSize(1);
    }

    @Test
    void chunksStayWithinLimitAndDoNotCrossChapters() {
        String para = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(12).strip();   // ~680 字符
        var chA = new Chapter("A", java.util.Collections.nCopies(10, "alpha " + para));
        var chB = new Chapter("B", java.util.Collections.nCopies(3, "beta " + para));
        String giant = "Sentence number one goes here. ".repeat(200);                                   // 单段 ~6000 字符
        var chC = new Chapter("C", List.of(giant));

        List<Chunk> chunks = chunker.chunk(new BookMeta("f", "Book", "Au", "u"), List.of(chA, chB, chC));

        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(2000));
        assertThat(chunks).filteredOn(c -> c.chapter().equals("A")).allSatisfy(c -> assertThat(c.text()).doesNotContain("beta"));
        assertThat(chunks).filteredOn(c -> c.chapter().equals("B")).allSatisfy(c -> assertThat(c.text()).doesNotContain("alpha"));
        assertThat(chunks).filteredOn(c -> c.chapter().equals("C")).hasSizeGreaterThan(3);
        // 相邻块有重叠
        var a = chunks.stream().filter(c -> c.chapter().equals("A")).toList();
        String tail = a.get(0).text().substring(a.get(0).text().length() - 100);
        assertThat(a.get(1).text()).contains(tail);
    }

    @Test
    void chunkIdsAreDeterministicAndFitMilvusIdField() {
        String id = Chunker.chunkId("Book", "CHAPTER I", 3);
        assertThat(id).hasSize(36).isEqualTo(Chunker.chunkId("Book", "CHAPTER I", 3));
        assertThat(id).isNotEqualTo(Chunker.chunkId("Book", "CHAPTER I", 4));
    }

    /** 在真实的 5 本书上跑一遍，打印每本的章节，人工抽查用。 */
    @Test
    void realBooks() throws Exception {
        Path dir = Path.of("data/raw");
        List<String> report = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                List<Chapter> chapters = splitter.split(cleaner.clean(Files.readString(f)));
                List<Chunk> chunks = chunker.chunk(new BookMeta(f.getFileName().toString(), f.getFileName().toString(), "x", "u"), chapters);

                assertThat(chapters).as(f.toString()).hasSizeGreaterThan(5);
                assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(2000));
                assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();

                report.add("== %s  chapters=%d chunks=%d".formatted(f.getFileName(), chapters.size(), chunks.size()));
                report.add(chapters.stream()
                        .map(c -> "   %-80.80s %4d paras".formatted(c.title(), c.paragraphs().size()))
                        .collect(Collectors.joining("\n")));
            }
        }
        System.out.println(String.join("\n", report));
    }
}
