package com.argus.rag.qa.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TableEntityExtractor} 的单元测试。
 *
 * <p>这套抽取规则同时服务两件事：查询蒸馏与实体级检索。
 * 因此它必须<b>准</b> —— 抽不准时宁可返回 {@code null} 让调用方保持原行为，
 * 也不要构造无意义查询去污染检索。</p>
 */
@DisplayName("表格实体抽取器")
class TableEntityExtractorTest {

    @Test
    @DisplayName("应抽出实体与目标列名")
    void shouldExtractEntityAndColumn() {
        TableEntityExtractor.TableLookup semester =
                TableEntityExtractor.extract("软件工程在第几学期开课？");
        assertThat(semester).isNotNull();
        assertThat(semester.entity()).isEqualTo("软件工程");
        assertThat(semester.columnName()).isEqualTo("授课学期");
        assertThat(semester.distilledQuery()).isEqualTo("软件工程 授课学期");

        TableEntityExtractor.TableLookup credit =
                TableEntityExtractor.extract("软件项目管理是几个学分？");
        assertThat(credit).isNotNull();
        assertThat(credit.entity()).isEqualTo("软件项目管理");
        assertThat(credit.columnName()).isEqualTo("学分");
    }

    @Test
    @DisplayName("含空格的课程名应完整抽出")
    void shouldExtractEntityWithSpaces() {
        TableEntityExtractor.TableLookup lookup =
                TableEntityExtractor.extract("分布数据库 GaussDB 在第几学期开课？");
        assertThat(lookup).isNotNull();
        assertThat(lookup.entity()).isEqualTo("分布数据库 GaussDB");
    }

    @Test
    @DisplayName("聚合类与总量类问题不应被抽取")
    void shouldNotExtractFromAggregateQuestions() {
        assertThat(TableEntityExtractor.extract("第5学期必修课程的学分是多少？")).isNull();
        assertThat(TableEntityExtractor.extract("软件工程专业毕业要求的总学分是多少？")).isNull();
        assertThat(TableEntityExtractor.extract("通识教育课程要求的最低学分是多少？")).isNull();
    }

    @Test
    @DisplayName("抽不出具体实体时返回 null")
    void shouldReturnNullWithoutConcreteEntity() {
        assertThat(TableEntityExtractor.extract("这门课在第几学期开课？")).isNull();
        assertThat(TableEntityExtractor.extract("该课程是几个学分？")).isNull();
        assertThat(TableEntityExtractor.extract(null)).isNull();
        assertThat(TableEntityExtractor.extract("   ")).isNull();
        assertThat(TableEntityExtractor.extractEntity("随便聊聊")).isNull();
        assertThat(TableEntityExtractor.distilledQuery("随便聊聊")).isNull();
    }
}
