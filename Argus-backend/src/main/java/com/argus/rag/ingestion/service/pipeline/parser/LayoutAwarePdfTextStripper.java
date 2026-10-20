package com.argus.rag.ingestion.service.pipeline.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 版面感知的 PDF 文本提取器：修复「竖排表头被逐字打散」的缺陷。
 *
 * <h3>问题</h3>
 * <p>中文表格的表头单元格常以<b>竖排</b>排版，例如 {@code 授课学期} 四个字在单元格内
 * 自上而下堆叠。{@link PDFTextStripper} 默认按 (y, x) 顺序线性输出，会把同一行高度上
 * 来自<b>不同单元格</b>的字符交错输出，于是表头被拆成二十多个单字符行：</p>
 *
 * <pre>
 * 课 / 程 / 类 / 别 / 修 / 读 / 类 / 别 / 课 / 程 / 号 ... / 授 / 课 / 学 / 期 / 周 / 学 / 时 / 学 / 分
 * </pre>
 *
 * <p>结果是<b>表头彻底不可读</b>：下游既不知道这张表有哪些列，也不知道哪一列是
 * 「授课学期」。表格数据行（例如 {@code 1960813 团队激励与沟通 5 2 1 24 12 12}）
 * 虽然完整，却失去了列语义，只能靠数值算术关系反推列含义 ——
 * 而「授课学期」列没有任何可交叉校验的算术关系，因此相关问题往往只能拒答。</p>
 *
 * <h3>修复</h3>
 * <p>把竖排文本的阅读顺序从「先行后列」纠正为「先列后行」：</p>
 * <ol>
 *     <li><b>完全沿用</b> {@link PDFTextStripper} 的行切分与词文本，
 *         正常文本逐字不变（零回归风险）</li>
 *     <li>识别<b>连续的单字符行</b>（达到 {@value #MIN_VERTICAL_RUN_LINES} 行以上）——
 *         这是竖排文本被逐字打散的特征</li>
 *     <li>仅对这些片段改用坐标重建：字符按 (x, y) 重排（x 相同者属于同一单元格，
 *         按 y 还原字序），再按 x 聚类得到各列，列间插入分隔符</li>
 * </ol>
 *
 * <p>修复后同一份文档的表头变为：</p>
 * <pre>
 * 课程类别 | 修读类别 | 课程号 | 课程名称 | 授课学期 | 周学时 | 学分 | 总学时 | 授课学时 | 实验学时 | 上机学时 | 课程实践学时 | 其他学时 | 备注
 * </pre>
 *
 * <h3>边界处理</h3>
 * <p>若某片段的每一列都只有 1 个字符，说明它其实是<b>横向文字</b>被换行拆散
 * （例如表格分组标题 {@code 通识教育课必修，共５８学分，１０６学时}），
 * 此时按列拼接但<b>不加分隔符</b>，以免产生 {@code 通 | 识 | 教 | 育} 这种噪声。</p>
 *
 * <p><b>注意</b>：本类<b>不</b>启用 {@code setSortByPosition(true)}。开启它会改变
 * PDFBox 的行切分，把竖排表头合并成 {@code 课修课授授实上程其} 这类交错行，
 * 反而让「单字符行」这一特征消失，使修复无从下手。</p>
 *
 * @author Argus-RAG Team
 * @since 1.1.0
 */
public class LayoutAwarePdfTextStripper extends PDFTextStripper {

    /** 连续多少个单字符行才认定为竖排文本。 */
    private static final int MIN_VERTICAL_RUN_LINES = 4;

    /** 列分隔符。 */
    private static final String COLUMN_SEPARATOR = " | ";

    /** x 聚类容差相对字符宽度的比例。 */
    private static final double X_TOLERANCE_RATIO = 0.2;

    /** x 聚类容差下限（磅）。 */
    private static final double MIN_X_TOLERANCE = 1.5;

    /** 当前页已解析出的行。 */
    private List<Line> currentPageLines;

    /** 正在累积的当前行。 */
    private Line currentLine;

    /** 已处理完的各页文本。 */
    private final List<String> pageTexts = new ArrayList<>();

    /**
     * 构造提取器。
     *
     * <p>刻意<b>不</b>调用 {@code setSortByPosition(true)}，以保持与历史行为一致的行切分。</p>
     *
     * @throws IOException 当父类初始化失败时抛出
     */
    public LayoutAwarePdfTextStripper() throws IOException {
        super();
    }

    /**
     * 提取整个文档的文本（含竖排修复）。
     *
     * @param document PDF 文档
     * @return 修复后的全文，页与页之间以换行分隔
     * @throws IOException 当 PDF 读取失败时抛出
     */
    public String getLayoutText(PDDocument document) throws IOException {
        pageTexts.clear();
        // getText 负责驱动 startPage/endPage/词与行回调的遍历；
        // 本类不向 output 写入内容，其返回值恒为空串，这里只取其副作用。
        super.getText(document);
        return String.join("\n", pageTexts);
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
        currentPageLines = new ArrayList<>();
        currentLine = new Line();
        super.startPage(page);
    }

    /**
     * PDFBox 按<b>词</b>回调本方法：这里同时累积词文本与字符坐标。
     *
     * @param text      该词文本
     * @param positions 该词各字符的坐标
     */
    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        if (currentLine == null) {
            return;
        }
        if (text != null) {
            currentLine.text.append(text);
        }
        if (positions != null) {
            currentLine.positions.addAll(positions);
        }
    }

    /** 词间分隔符：还原到一个空格，保持与原实现一致。 */
    @Override
    protected void writeWordSeparator() {
        if (currentLine != null) {
            currentLine.text.append(' ');
        }
    }

    /** 行边界：把当前行收进页面行列表。 */
    @Override
    protected void writeLineSeparator() {
        flushLine();
    }

    /** 段落分隔：同样意味着一行终结。 */
    @Override
    protected void writeParagraphSeparator() {
        flushLine();
    }

    @Override
    protected void writeParagraphStart() {
        // 段落结构对纯文本重建没有意义，忽略
    }

    @Override
    protected void writeParagraphEnd() {
        flushLine();
    }

    @Override
    protected void writeCharacters(TextPosition text) {
        // 字符级回调不参与行重建，忽略
    }

    @Override
    protected void writeString(String text) {
        // 非结构化文本回调不参与行重建，忽略
    }

    /** 把当前行收进页面行列表并换新行。 */
    private void flushLine() {
        if (currentPageLines != null && currentLine != null && !currentLine.isEmpty()) {
            currentPageLines.add(currentLine);
        }
        currentLine = new Line();
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        flushLine();
        if (currentPageLines != null) {
            pageTexts.add(buildPageText(currentPageLines));
        }
        super.endPage(page);
    }

    // ------------------------------------------------------------------
    // 页面级重建
    // ------------------------------------------------------------------

    /**
     * 把一页的行重建为文本，并把连续单字符行（竖排文本）合并成一行。
     *
     * @param lines 该页的各行
     * @return 重建后的页面文本
     */
    private String buildPageText(List<Line> lines) {
        List<String> out = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            if (!lines.get(index).isSingleCharLine()) {
                out.add(lines.get(index).text.toString());
                index++;
                continue;
            }
            int end = index;
            while (end < lines.size() && lines.get(end).isSingleCharLine()) {
                end++;
            }
            if (end - index >= MIN_VERTICAL_RUN_LINES) {
                List<TextPosition> runChars = new ArrayList<>();
                for (int cursor = index; cursor < end; cursor++) {
                    runChars.addAll(lines.get(cursor).positions);
                }
                out.add(rebuildVerticalRun(runChars));
            } else {
                // 单字符行不够长，保持原样，避免误伤正常文本
                for (int cursor = index; cursor < end; cursor++) {
                    out.add(lines.get(cursor).text.toString());
                }
            }
            index = end;
        }
        return String.join("\n", out);
    }

    /**
     * 恢复一段竖排文本：按 (x, y) 重排后按列聚类，列内按 y 还原字序。
     *
     * <p>这是整个修复的核心 —— 竖排单元格内的字符 <b>x 相同、y 递增</b>，
     * 因此「x 优先、y 次之」才是正确的阅读顺序。</p>
     *
     * @param chars 该竖排片段内的全部字符
     * @return 重建后的单行文本
     */
    private String rebuildVerticalRun(List<TextPosition> chars) {
        List<TextPosition> sorted = new ArrayList<>(chars);
        sorted.sort(Comparator.comparingDouble(TextPosition::getX)
                .thenComparingDouble(TextPosition::getY));

        double tolerance = xTolerance(sorted);
        List<List<TextPosition>> columns = new ArrayList<>();
        double anchorX = Double.NaN;
        for (TextPosition position : sorted) {
            if (columns.isEmpty() || Math.abs(position.getX() - anchorX) > tolerance) {
                columns.add(new ArrayList<>());
                anchorX = position.getX();
            }
            columns.get(columns.size() - 1).add(position);
        }

        List<String> cells = new ArrayList<>();
        for (List<TextPosition> column : columns) {
            column.sort(Comparator.comparingDouble(TextPosition::getY));
            StringBuilder builder = new StringBuilder();
            for (TextPosition position : column) {
                builder.append(position.getUnicode());
            }
            cells.add(builder.toString());
        }

        // 每一列都只有 1 个字符 => 其实是横向文字被换行拆散，直接无分隔拼接
        boolean anyMultiCharCell = cells.stream()
                .anyMatch(cell -> cell.codePointCount(0, cell.length()) >= 2);
        return anyMultiCharCell ? String.join(COLUMN_SEPARATOR, cells) : String.join("", cells);
    }

    /**
     * 计算 x 聚类容差：取字符宽度中位数的固定比例，并设下限。
     *
     * <p>同一单元格内各字符的 x 完全相同，相邻单元格的 x 间距明显大于字宽的一部分，
     * 因此以字宽比例作为容差既能容忍坐标抖动，又不会把相邻列合并。</p>
     *
     * @param chars 参与聚类的字符
     * @return 容差值（磅）
     */
    private double xTolerance(List<TextPosition> chars) {
        List<Float> widths = new ArrayList<>();
        for (TextPosition position : chars) {
            if (position.getWidth() > 0f) {
                widths.add(position.getWidth());
            }
        }
        if (widths.isEmpty()) {
            return MIN_X_TOLERANCE;
        }
        widths.sort(Float::compare);
        double median = widths.get(widths.size() / 2);
        return Math.max(MIN_X_TOLERANCE, median * X_TOLERANCE_RATIO);
    }

    /** 一行：累积的词文本 + 各字符坐标。 */
    private static final class Line {

        /** PDFBox 渲染出的行文本（逐字保持原样）。 */
        private final StringBuilder text = new StringBuilder();

        /** 该行各字符的坐标。 */
        private final List<TextPosition> positions = new ArrayList<>();

        private boolean isEmpty() {
            return text.length() == 0 && positions.isEmpty();
        }

        /**
         * 是否为「仅含一个非空白字符」的行 —— 竖排文本被打散后的特征。
         *
         * @return 是则返回 {@code true}
         */
        private boolean isSingleCharLine() {
            String trimmed = text.toString().strip();
            return positions.size() == 1
                    && trimmed.codePointCount(0, trimmed.length()) == 1;
        }
    }
}
