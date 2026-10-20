package com.argus.rag.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询规划中「全量覆盖」判定规则的单元测试。
 *
 * <p>计数与枚举类问题的答案不在任何单个切片里，必须遍历整篇文档。
 * 提示词虽已要求模型选择 {@code GLOBAL}，但实测不稳定，因此加确定性兜底。
 * 这条规则必须<b>精准</b>：误命中会把普通单点问题也变成全量遍历，抬高成本与延迟。</p>
 */
@DisplayName("查询规划的全量覆盖判定")
class QueryPlanningServiceCoverageTest {

    @Test
    @DisplayName("计数与枚举类问题应判定为需要全量覆盖")
    void shouldDetectEnumerationQuestions() {
        assertThat(QueryPlanningService.requiresFullCoverage(
                "培养方案的课程设置表中，第5学期一共开设了多少门课程？")).isTrue();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "这个专业一共有多少门必修课？")).isTrue();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "各学期的学分是怎么分布的？")).isTrue();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "每学期开几门课？")).isTrue();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "课程设置表里有哪些课程？")).isTrue();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "所有课程加起来总共多少学分？")).isTrue();
    }

    @Test
    @DisplayName("单点事实类问题不应判定为需要全量覆盖")
    void shouldNotMatchSingleFactQuestions() {
        assertThat(QueryPlanningService.requiresFullCoverage(
                "数据结构在第几学期开课？")).isFalse();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "软件质量保证与测试是几个学分？")).isFalse();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "软件工程专业毕业要求的总学分是多少？")).isFalse();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "通识教育课程要求的最低学分是多少？")).isFalse();
        assertThat(QueryPlanningService.requiresFullCoverage(
                "集中性实践教学环节要求的最低学分是多少？")).isFalse();
    }

    @Test
    @DisplayName("空问题不应抛异常")
    void shouldHandleNullAndBlank() {
        assertThat(QueryPlanningService.requiresFullCoverage(null)).isFalse();
        assertThat(QueryPlanningService.requiresFullCoverage("")).isFalse();
    }
}
