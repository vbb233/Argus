package com.argus.rag.ingestion.service.pipeline.transformer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StructureAwareChunkTransformer} 的表格上下文回填测试。
 *
 * <p>关注点：表格被切成多个分块后，只有第一个分块含有表名与表头，
 * 其余分块只剩裸数据行。回填机制应把表名 + 表头补到这些分块上，
 * 否则分块失去列语义（「哪一列是授课学期」）与表格级语义（「这是哪张表」）。</p>
 */
@DisplayName("分块器的表格上下文回填")
class StructureAwareChunkTransformerTest {

    private static final String TITLE = "软件工程专业课程设置表";
    private static final String HEADER = "课程类别 | 修读类别 | 课程号 | 课程名称 | 授课学期 "
            + "| 周学时 | 学分 | 总学时 | 授课学时 | 实验学时 | 上机学时 | 备注";

    private final StructureAwareChunkTransformer transformer =
            new StructureAwareChunkTransformer(new ChunkingProperties(200, 320, 32));

    /**
     * 构造一份「表名 + 表头 + 大量数据行」的文档。
     * 数据行足够多，确保表格会被切成多个分块。
     */
    private List<Document> chunkTableDocument() {
        StringBuilder text = new StringBuilder();
        text.append(TITLE).append('\n');
        text.append(HEADER).append('\n');
        for (int i = 0; i < 40; i++) {
            text.append(String.format("196%04d 测试课程%d %d %d %d %d %d %d%n",
                    i, i, (i % 8) + 1, 2, 2, 32, 20, 12));
        }
        Document source = new Document("doc-1", text.toString(), Map.of("fileName", "plan.pdf"));
        return transformer.apply(List.of(source));
    }

    @Test
    @DisplayName("表格被切成多块时，非首块也应回填表名与表头")
    void shouldPropagateTableTitleAndHeaderToLaterChunks() {
        List<String> texts = chunkTableDocument().stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        assertThat(texts).as("文档应被切成多个分块").hasSizeGreaterThan(1);

        // 首块自带表名，因此不会被加上前缀；后续分块必须靠回填拿到表名。
        long injected = texts.stream()
                .filter(text -> text.startsWith("[所属表格] " + TITLE))
                .count();
        assertThat(injected)
                .as("应有分块通过回填获得表名（首块自带表名，不计入）")
                .isPositive();

        // 每一个分块（无论原生还是回填）都应能知道自己属于哪张表、哪一列是什么
        assertThat(texts).allSatisfy(text -> {
            assertThat(text).contains(TITLE);
            assertThat(text).contains("授课学期");
        });
    }

    @Test
    @DisplayName("回填的表头应包含授课学期与学分两列")
    void propagatedHeaderShouldNameSemesterAndCreditColumns() {
        String joined = chunkTableDocument().stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        assertThat(joined).contains("授课学期");
        assertThat(joined).contains("学分");
    }

    @Test
    @DisplayName("不含数据行的分块不应被回填表格上下文")
    void shouldNotInjectContextIntoNonTableChunks() {
        String prose = "软件工程专业课程设置表\n"
                + "本专业培养服务于社会主义建设事业的应用型高级软件人才，"
                + "学生毕业后能够从事软件设计、开发、测试与项目管理等工作。\n"
                + "本专业学制四年，授予工学学士学位。\n";
        Document source = new Document("doc-2", prose, Map.of("fileName", "intro.pdf"));

        List<Document> chunks = transformer.apply(List.of(source));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getText()).doesNotContain("[表格表头] ");
            assertThat(chunk.getText()).doesNotContain("[所属表格] ");
        });
    }

    @Test
    @DisplayName("分块应保留原文内容，仅在前面追加表格上下文")
    void chunkShouldStillContainOriginalRows() {
        List<String> texts = chunkTableDocument().stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        assertThat(texts.stream().collect(Collectors.joining("\n")))
                .contains("测试课程1")
                .contains("测试课程20");
    }
}
