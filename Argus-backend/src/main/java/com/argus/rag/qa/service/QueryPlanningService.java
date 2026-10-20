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
            log.info("查询规划完成: strategy={}, queries={}", validatedPlan.strategy(), validatedPlan.queries());
            return validatedPlan;
        } catch (RuntimeException exception) {
            log.warn("查询规划失败，回退为直接检索: question={}", normalizedQuestion, exception);
            return QueryPlanResult.fallback(normalizedQuestion);
        }
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
