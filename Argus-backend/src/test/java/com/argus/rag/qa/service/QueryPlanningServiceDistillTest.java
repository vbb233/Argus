package com.argus.rag.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「检索查询蒸馏」的单元测试。
 *
 * <p>动机：用户说「在第几学期开课」，文档的列名叫「授课学期」，词面完全不重叠。
 * 实测把查询蒸馏成「实体 + 列名」后，含目标课程行的分块在关键词通道
 * 从第 10 位升到第 2 位；而<b>只往长查询后面加词是无效的</b>（第 7 位），
 * 因为「在 / 第 / 几 / 学期 / 开课」这些通用词在大段叙述文中反复出现。</p>
 *
 * <p>因此这条规则要精准：抽出实体才蒸馏，抽不准就返回 {@code null}
 * 保持原有行为，绝不硬造查询。</p>
 */
@DisplayName("检索查询蒸馏")
class QueryPlanningServiceDistillTest {

    @Test
    @DisplayName("「X在第几学期开课」应蒸馏为「X 授课学期」")
    void shouldDistillSemesterQuestions() {
        assertThat(QueryPlanningService.distilledTableQuery("软件工程在第几学期开课？"))
                .isEqualTo("软件工程 授课学期");
        assertThat(QueryPlanningService.distilledTableQuery("数据结构在第几学期开课？"))
                .isEqualTo("数据结构 授课学期");
        assertThat(QueryPlanningService.distilledTableQuery("团队激励与沟通在第几学期开课？"))
                .isEqualTo("团队激励与沟通 授课学期");
    }

    @Test
    @DisplayName("「X是几个学分」应蒸馏为「X 学分」")
    void shouldDistillCreditQuestions() {
        assertThat(QueryPlanningService.distilledTableQuery("软件工程是几个学分？"))
                .isEqualTo("软件工程 学分");
        assertThat(QueryPlanningService.distilledTableQuery("软件项目管理是几个学分？"))
                .isEqualTo("软件项目管理 学分");
    }

    @Test
    @DisplayName("聚合类与总量类问题不应被蒸馏")
    void shouldNotDistillAggregateQuestions() {
        assertThat(QueryPlanningService.distilledTableQuery(
                "第5学期必修课程的学分是多少？")).isNull();
        assertThat(QueryPlanningService.distilledTableQuery(
                "软件工程专业毕业要求的总学分是多少？")).isNull();
        assertThat(QueryPlanningService.distilledTableQuery(
                "通识教育课程要求的最低学分是多少？")).isNull();
    }

    @Test
    @DisplayName("抽不出具体实体时不应蒸馏，避免构造无意义查询")
    void shouldNotDistillWithoutConcreteEntity() {
        assertThat(QueryPlanningService.distilledTableQuery("这门课在第几学期开课？")).isNull();
        assertThat(QueryPlanningService.distilledTableQuery("该课程是几个学分？")).isNull();
        assertThat(QueryPlanningService.distilledTableQuery(null)).isNull();
        assertThat(QueryPlanningService.distilledTableQuery("")).isNull();
    }
}
