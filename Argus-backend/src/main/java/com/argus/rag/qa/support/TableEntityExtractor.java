package com.argus.rag.qa.support;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「实体 + 口语化属性问法」的问题里抽取实体与目标列名。
 *
 * <p>例如「软件工程在第几学期开课？」→ 实体 {@code 软件工程}、列名 {@code 授课学期}。</p>
 *
 * <h3>为什么需要</h3>
 * <p>表格的列名用的是文档自己的措辞（「授课学期」），而用户自然会说「在第几学期开课」。
 * 两者词面完全不重叠，关键词检索于是只能靠实体名这类词排序，大段叙述文字反而胜出。
 * 抽出「实体 + 列名」之后，可以同时服务两件事：</p>
 * <ol>
 *     <li><b>查询蒸馏</b>：把长问题压缩成 {@code 实体 列名} 参与检索融合</li>
 *     <li><b>实体级检索</b>：按实体名精确查行级分块（结构化通道，绕开排序）</li>
 * </ol>
 *
 * <h3>设计约束</h3>
 * <p>抽不准时必须返回 {@code null}，让调用方保持原有行为 ——
 * 宁可少一次优化，也不要构造无意义查询去污染检索结果。</p>
 *
 * @author Argus-RAG Team
 * @since 1.1.0
 */
public final class TableEntityExtractor {

    /** 实体 + 目标列名的抽取结果。 */
    public record TableLookup(String entity, String columnName) {

        /**
         * 蒸馏后的检索查询：{@code 实体 列名}。
         *
         * @return 短查询文本
         */
        public String distilledQuery() {
            return entity + " " + columnName;
        }
    }

    /**
     * 提问模式 → 文档列名映射。
     *
     * <p>顺序有意义：越具体的模式越靠前。</p>
     */
    private static final List<Map.Entry<Pattern, String>> TABLE_LOOKUPS = List.of(
            Map.entry(Pattern.compile("^\\s*(.+?)\\s*在?第几(?:个)?学期"), "授课学期"),
            Map.entry(Pattern.compile("^\\s*(.+?)\\s*(?:是|有)?(?:几|多少)个?学分"), "学分"),
            Map.entry(Pattern.compile("^\\s*(.+?)\\s*(?:是|有)?(?:几|多少)个?学时"), "学时"));

    /** 不像实体名的片段（指示代词等） */
    private static final List<String> NON_ENTITY_MARKERS = List.of(
            "这门", "那门", "该课", "这个", "那个", "它", "他", "上述", "前面");

    /** 实体名长度上限 */
    private static final int MAX_ENTITY_LENGTH = 24;

    private TableEntityExtractor() {
    }

    /**
     * 抽取实体与目标列名。
     *
     * @param question 用户问题
     * @return 抽取结果；无法可靠抽取时返回 {@code null}
     */
    public static TableLookup extract(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        for (Map.Entry<Pattern, String> entry : TABLE_LOOKUPS) {
            Matcher matcher = entry.getKey().matcher(question);
            if (!matcher.find()) {
                continue;
            }
            String entity = matcher.group(1).strip();
            if (!looksLikeEntity(entity)) {
                return null;
            }
            return new TableLookup(entity, entry.getValue());
        }
        return null;
    }

    /**
     * 抽取实体名（不含列名）。
     *
     * @param question 用户问题
     * @return 实体名；无法可靠抽取时返回 {@code null}
     */
    public static String extractEntity(String question) {
        TableLookup lookup = extract(question);
        return lookup == null ? null : lookup.entity();
    }

    /**
     * 生成蒸馏后的检索查询。
     *
     * @param question 用户问题
     * @return 短查询；无法抽取时返回 {@code null}
     */
    public static String distilledQuery(String question) {
        TableLookup lookup = extract(question);
        return lookup == null ? null : lookup.distilledQuery();
    }

    // 判断抽出的片段是否像一个具体实体名（课程名等）
    private static boolean looksLikeEntity(String candidate) {
        if (candidate.length() < 2 || candidate.length() > MAX_ENTITY_LENGTH) {
            return false;
        }
        for (String marker : NON_ENTITY_MARKERS) {
            if (candidate.contains(marker)) {
                return false;
            }
        }
        return true;
    }
}
