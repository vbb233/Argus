package com.argus.rag.qa.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.qa.model.QueryPlanResult;
import com.argus.rag.qa.model.QueryPlanStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询规划服务。
 * <p>
 * 调用大模型分析用户问题，决定采用哪种检索策略（直接、重写或分解），
 * 并生成对应的检索语句列表。规划失败时自动回退为 DIRECT 策略。
 * </p>
 */
@Service
public class QueryPlanningService {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanningService.class);

    /** 检索语句最大数量限制 */
    private static final int MAX_QUERY_COUNT = 3;

    /**
     * 需要「整篇文档覆盖」才能回答的问题特征：计数、枚举、跨条目汇总。
     *
     * <p>这类问题的答案<b>不出现在任何单个切片里</b> —— 例如
     * 「第5学期一共开设了多少门课程？」必须把散布在多个切片中的全部课程行
     * 汇总后才能计数。若交给相关度检索，只会拿到 4~5 个切片，
     * 模型据此计数必然少数（实测答「5 项」，实际 8 项）。</p>
     *
     * <p>提示词已要求模型在遇到这类问题时选择 {@code GLOBAL}，但实测选择并不稳定。
     * 因此这里加一条<b>确定性兜底</b>：命中即强制走 {@code GLOBAL}，
     * 不再依赖大模型判断。</p>
     */
    private static final Pattern FULL_COVERAGE_QUESTION = Pattern.compile(
            "多少门|几门|哪几门|哪些课"
                    + "|一共|总共|共计"
                    + "|所有的?课程|全部课程"
                    + "|各学期|每个学期|每学期");

    /**
     * 提问用语 → 文档列名的词汇映射，用于<b>检索查询蒸馏</b>（不进入生成提示词）。
     *
     * <p>表格的列名是文档自己的措辞（「授课学期」），而用户自然会说
     * 「在第几学期开课」。两者词面完全不重叠，关键词检索于是只能靠课程名
     * 这类高词频词排序，大段叙述文字反而胜出。</p>
     *
     * <p>实测（同一份培养方案、同一个问题「软件工程在第几学期开课？」，
     * 目标分块为含该课程行的行级分块）：</p>
     * <pre>
     *   "软件工程在第几学期开课"           -> 目标分块排第 10 位（进不了 top-5）
     *   "软件工程在第几学期开课 授课学期"  -> 排第  7 位（只加词没用）
     *   "软件工程 授课学期"               -> 排第  2 位  ← 蒸馏后才有效
     * </pre>
     *
     * <p><b>为什么"加词"无效而"蒸馏"有效</b>：长查询里的
     * 「在 / 第 / 几 / 学期 / 开课」这些通用词在大段叙述文中反复出现，
     * 把目标分块压了下去；只有把查询压缩成「实体 + 列名」，
     * 词密度才回到目标分块这一侧。</p>
     */
    private static final List<Map.Entry<Pattern, String>> TABLE_LOOKUPS = List.of(
            Map.entry(Pattern.compile("^\\s*(.+?)\\s*在?第几(?:个)?学期"), "授课学期"),
            Map.entry(Pattern.compile("^\\s*(.+?)\\s*(?:是|有)?(?:几|多少)个?学分"), "学分"),
            Map.entry(Pattern.compile("^\\s*(.+?)\\s*(?:是|有)?(?:几|多少)个?学时"), "学时"));

    private final ChatClient queryPlanningChatClient;
    private final PromptTemplate queryPlanningUserPromptTemplate;

    /**
     * 构造函数。
     *
     * @param queryPlanningChatClient         查询规划专用的 ChatClient
     * @param queryPlanningUserPromptTemplate 查询规划用户提示词模板
     */
    public QueryPlanningService(
            @Qualifier("queryPlanningChatClient") ChatClient queryPlanningChatClient,
            @Qualifier("queryPlanningUserPromptTemplate") PromptTemplate queryPlanningUserPromptTemplate
    ) {
        this.queryPlanningChatClient = queryPlanningChatClient;
        this.queryPlanningUserPromptTemplate = queryPlanningUserPromptTemplate;
    }

    /**
     * 对用户问题执行查询规划。
     * <p>
     * 调用大模型分析问题并返回检索策略和检索语句，
     * 失败时回退为 DIRECT 策略。
     * </p>
     *
     * @param question 用户原始问题
     * @return 查询规划结果
     */
    public QueryPlanResult plan(String question) {
        String normalizedQuestion = requireQuestion(question);
        if (requiresFullCoverage(normalizedQuestion)) {
            log.info("查询规划: 命中全量覆盖规则, 强制 GLOBAL, question={}", normalizedQuestion);
            return new QueryPlanResult(QueryPlanStrategy.GLOBAL, List.of(normalizedQuestion));
        }
        try {

            Prompt planPrompt = queryPlanningUserPromptTemplate.create(Map.of("question", normalizedQuestion));

            QueryPlanResult rawResult = queryPlanningChatClient.prompt(planPrompt)
//                    .user(user -> user.text(renderUserPrompt(normalizedQuestion)))
                    .call()
                    .entity(QueryPlanResult.class);
            QueryPlanResult validatedPlan = validatePlan(rawResult, normalizedQuestion);
            QueryPlanResult retrievalPlan = expandForRetrieval(validatedPlan, normalizedQuestion);
            log.info("查询规划完成: strategy={}, queries={}",
                    retrievalPlan.strategy(), retrievalPlan.queries());
            return retrievalPlan;
        } catch (RuntimeException exception) {
            log.warn("查询规划失败，回退为直接检索: question={}", normalizedQuestion, exception);
            return expandForRetrieval(QueryPlanResult.fallback(normalizedQuestion), normalizedQuestion);
        }
    }

    /**
     * 把「实体 + 口语化属性问法」的问题蒸馏成「实体 + 文档列名」的检索查询。
     *
     * <p>例如「软件工程在第几学期开课？」蒸馏为 {@code 软件工程 授课学期}。
     * 仅在能明确抽出实体时生效；抽出结果不像实体（过长、含指示代词）时返回
     * {@code null}，保持原有检索行为不变。</p>
     *
     * @param question 用户问题
     * @return 蒸馏后的查询；无法蒸馏时返回 {@code null}
     */
    static String distilledTableQuery(String question) {
        if (question == null) {
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
            return entity + " " + entry.getValue();
        }
        return null;
    }

    // 判断抽出的片段是否像一个具体实体名（课程名等）
    private static boolean looksLikeEntity(String candidate) {
        if (candidate.length() < 2 || candidate.length() > 24) {
            return false;
        }
        return !candidate.contains("这门") && !candidate.contains("那门")
                && !candidate.contains("该课程") && !candidate.contains("这个")
                && !candidate.contains("它");
    }

    /**
     * 为检索追加一路「蒸馏查询」。
     *
     * <p>只影响 {@link QueryPlanResult#queries()}，即检索用查询；生成答案时使用的是
     * 用户原始问题，不会被污染。原始查询保留，蒸馏查询作为<b>额外一路</b>参与融合，
     * 因此不会削弱原有召回。</p>
     *
     * <p>{@code GLOBAL} 策略不按查询文本检索（直接遍历全部切片），跳过。</p>
     *
     * @param plan     规划结果
     * @param question 用户问题
     * @return 可能追加了蒸馏查询的规划结果
     */
    private QueryPlanResult expandForRetrieval(QueryPlanResult plan, String question) {
        if (plan.strategy() == QueryPlanStrategy.GLOBAL) {
            return plan;
        }
        String distilled = distilledTableQuery(question);
        if (distilled == null) {
            return plan;
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>(plan.queries());
        if (!queries.add(distilled)) {
            return plan;
        }
        log.info("检索查询蒸馏: {} -> {}", question, distilled);
        return new QueryPlanResult(plan.strategy(), List.copyOf(queries));
    }

    /**
     * 校验规划结果，确保策略和检索语句有效。
     * 根据不同策略构建最终的检索语句列表。
     */
    private QueryPlanResult validatePlan(QueryPlanResult rawResult, String originalQuestion) {
        if (rawResult == null || rawResult.strategy() == null) {
            return QueryPlanResult.fallback(originalQuestion);
        }
        if (rawResult.strategy() == QueryPlanStrategy.GLOBAL) {
            return new QueryPlanResult(QueryPlanStrategy.GLOBAL, List.of(originalQuestion));
        }
        Set<String> normalizedQueries = normalizeQueries(rawResult.queries());
        if (normalizedQueries.isEmpty()) {
            return QueryPlanResult.fallback(originalQuestion);
        }
        List<String> finalQueries = switch (rawResult.strategy()) {
            case DIRECT -> List.of(originalQuestion);
            case REWRITE -> buildRewriteQueries(originalQuestion, normalizedQueries);
            case DECOMPOSE -> limitQueries(normalizedQueries);
            case GLOBAL -> List.of(originalQuestion);
        };
        if (finalQueries.isEmpty()) {
            return QueryPlanResult.fallback(originalQuestion);
        }
        return new QueryPlanResult(rawResult.strategy(), finalQueries);
    }

    /**
     * 判断问题是否必须遍历整篇文档才能回答（计数 / 枚举 / 跨条目汇总）。
     *
     * <p>可见性为包级，便于在不启动 Spring 上下文的情况下直接单测这条规则。</p>
     *
     * @param question 用户问题
     * @return 需要全量覆盖时返回 {@code true}
     */
    static boolean requiresFullCoverage(String question) {
        return question != null && FULL_COVERAGE_QUESTION.matcher(question).find();
    }

    /** 构建 REWRITE 策略的检索语句：原始问题 + 重写后的语句 */
    private List<String> buildRewriteQueries(String originalQuestion, Set<String> normalizedQueries) {        LinkedHashSet<String> rewriteQueries = new LinkedHashSet<>();
        rewriteQueries.add(originalQuestion);
        rewriteQueries.addAll(normalizedQueries);
        return limitQueries(rewriteQueries);
    }

    /** 规范化检索语句：去除空白、去重 */
    private Set<String> normalizeQueries(List<String> queries) {
        LinkedHashSet<String> normalizedQueries = new LinkedHashSet<>();
        if (queries == null) {
            return normalizedQueries;
        }
        for (String query : queries) {
            if (!StringUtils.hasText(query)) {
                continue;
            }
            String normalized = query.replaceAll("\\s+", " ").trim();
            if (StringUtils.hasText(normalized)) {
                normalizedQueries.add(normalized);
            }
        }
        return normalizedQueries;
    }

    /** 限制检索语句数量不超过 {@link #MAX_QUERY_COUNT} */
    private List<String> limitQueries(Set<String> queries) {
        return queries.stream()
                .limit(MAX_QUERY_COUNT)
                .toList();
    }

    /** 渲染用户提示词模板（当前未使用，保留备用） */
    private String renderUserPrompt(String question) {
        return queryPlanningUserPromptTemplate.render(Map.of("question", question));
    }

    /** 校验问题非空并规范化空白字符 */
    private String requireQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException("问题不能为空");
        }
        return question.replaceAll("\\s+", " ").trim();
    }
}
