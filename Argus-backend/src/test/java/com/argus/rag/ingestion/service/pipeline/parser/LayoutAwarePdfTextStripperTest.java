package com.argus.rag.ingestion.service.pipeline.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LayoutAwarePdfTextStripper} 的单元测试。
 *
 * <p>用 PDFBox 现场合成一份包含「竖排表头 + 正常数据行」的 PDF，
 * 验证竖排片段被还原为单行且带列分隔符，同时正常文本不受影响。</p>
 *
 * <p>测试刻意使用 ASCII 字符：一是避免依赖 CJK 字体（Helvetica 由 PDFBox 内置），
 * 二是能把关注点收敛到<b>版面重建</b>本身，而不是字形编码。</p>
 */
@DisplayName("版面感知 PDF 文本提取器")
class LayoutAwarePdfTextStripperTest {

    /**
     * 合成 PDF：三个竖排表头单元格，各字符 y 坐标互不相同
     * （否则 PDFBox 会把它们归进同一行，就不是"单字符行"了），
     * 下方再放一行正常横向数据。
     */
    private byte[] verticalHeaderPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(PDType1Font.HELVETICA, 12f);

                // 单元格一：NO  —— 同一 x，y 递减排布
                drawChar(content, "N", 50f, 700f);
                drawChar(content, "O", 50f, 688f);

                // 单元格二：NAME
                drawChar(content, "N", 120f, 703f);
                drawChar(content, "A", 120f, 691f);
                drawChar(content, "M", 120f, 679f);
                drawChar(content, "E", 120f, 667f);

                // 单元格三：QTY
                drawChar(content, "Q", 200f, 699f);
                drawChar(content, "T", 200f, 687f);
                drawChar(content, "Y", 200f, 675f);

                // 正常横向数据行：应原样保留
                content.beginText();
                content.newLineAtOffset(50f, 600f);
                content.showText("ROW1 5 2 1 24 12 12");
                content.endText();
            }

            document.save(out);
            return out.toByteArray();
        }
    }

    private void drawChar(PDPageContentStream content, String text, float x, float y)
            throws Exception {
        content.beginText();
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private String parse(byte[] pdf) throws Exception {
        return new PdfDocumentParser().parse(new ByteArrayInputStream(pdf));
    }

    @Test
    @DisplayName("竖排表头应还原为单行并带列分隔符")
    void shouldRebuildVerticallyTypesetHeader() throws Exception {
        String text = parse(verticalHeaderPdf());

        assertThat(text)
                .as("竖排表头应被还原为一行，且列之间以分隔符隔开")
                .contains("NO | NAME | QTY");
    }

    @Test
    @DisplayName("竖排表头不应再以逐字单行的形式残留")
    void shouldNotLeaveSingleCharLines() throws Exception {
        String text = parse(verticalHeaderPdf());

        long singleCharLines = text.lines()
                .map(String::strip)
                .filter(line -> line.codePointCount(0, line.length()) == 1)
                .count();

        assertThat(singleCharLines)
                .as("所有竖排字符都应被合并，不应只剩孤立单字符行")
                .isZero();
    }

    @Test
    @DisplayName("同一页内的正常横向文本应逐字保持不变")
    void shouldKeepNormalTextIntact() throws Exception {
        String text = parse(verticalHeaderPdf());

        assertThat(text).contains("ROW1 5 2 1 24 12 12");
    }

    @Test
    @DisplayName("不足四行的单字符行不应被当作竖排文本处理")
    void shouldNotTreatShortSingleCharRunsAsVertical() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(PDType1Font.HELVETICA, 12f);
                // 只有 2 个单字符行，低于阈值
                drawChar(content, "X", 50f, 700f);
                drawChar(content, "Y", 50f, 688f);
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        String text = parse(pdf);

        assertThat(text.lines().map(String::strip).filter(line -> line.equals("X")))
                .as("低于阈值的单字符行应保持原样")
                .isNotEmpty();
    }
}
