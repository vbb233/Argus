package com.argus.rag.ingestion.service.pipeline.parser;

import com.argus.rag.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

/**
 * PDF 文档解析器，基于 Apache PDFBox。
 *
 * <h3>支持的格式</h3>
 * <p>支持扩展名为 {@code .pdf} 的 PDF 文档。</p>
 *
 * <h3>解析策略</h3>
 * <ol>
 *     <li>使用 PDFBox 的 {@link PDDocument#load(InputStream)} 加载 PDF 文档</li>
 *     <li>通过 {@link LayoutAwarePdfTextStripper} 提取全部文本内容（按页码顺序）</li>
 *     <li>统一换行符：将所有 {@code \r\n} 和独立的 {@code \r} 转换为 {@code \n}</li>
 *     <li>去除首尾空白字符</li>
 * </ol>
 *
 * <h3>为什么不用默认的 {@link PDFTextStripper}</h3>
 * <p>默认提取器按 (y, x) 顺序线性输出，会把<b>竖排表头</b>逐字打散成
 * {@code 课 / 程 / 类 / 别 / 授 / 课 / 学 / 期} 这样的碎片，使表格列语义彻底丢失
 * （详见 {@link LayoutAwarePdfTextStripper} 的类注释）。因此这里改用版面感知实现：
 * 它在默认行切分的基础上只修复竖排片段，其余文本保持原样。</p>
 *
 * <h3>异常处理</h3>
 * <p>PDFBox 在遇到损坏文件、加密文件或非 PDF 格式时可能抛出多种异常
 * （如 {@link IOException}），解析器统一包装为包含 "PDF 文档解析失败" 消息的
 * {@link com.argus.rag.common.exception.BusinessException}。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public class PdfDocumentParser implements DocumentParser {

    /**
     * 判断是否支持指定扩展名（大小写不敏感）。
     *
     * @param extension 文件扩展名
     * @return 当扩展名为 "pdf"（忽略大小写）时返回 {@code true}
     */
    @Override
    public boolean supports(String extension) {
        return "pdf".equalsIgnoreCase(extension);
    }

    /**
     * 解析 PDF 文件内容为纯文本。
     *
     * <p>使用 PDFBox 加载 PDF 文档，并通过 {@link LayoutAwarePdfTextStripper}
     * 提取文本（含竖排表头修复）。解析完成后会自动关闭 PDF 文档资源。</p>
     *
     * @param inputStream PDF 文件的输入流
     * @return 解析后的纯文本内容
     * @throws com.argus.rag.common.exception.BusinessException 当 PDF 文件损坏、
     *         格式非法或读取异常时抛出
     */
    @Override
    public String parse(InputStream inputStream) {
        try (PDDocument document = PDDocument.load(inputStream)) {
            String text = new LayoutAwarePdfTextStripper().getLayoutText(document);
            return text.replace("\r\n", "\n").replace('\r', '\n').trim();
        } catch (IOException exception) {
            throw new BusinessException("PDF 文档解析失败", exception);
        }
    }
}
