package com.argus.rag.metrics.cost;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 计量与计费配置属性，前缀 {@code argus.rag.llm}。
 *
 * <h3>为什么需要这个类</h3>
 * <p>历史版本把对话模型名硬编码为常量 {@code MODEL_NAME = "qwen-plus"}，
 * 散落在 {@code QaService}、{@code AssistantService}、{@code QaChatService}
 * 三处，产生了两个问题：</p>
 * <ol>
 *     <li><b>数据失真</b>：{@code qa_records.model_name} 与
 *         {@code llm_usage_records.model_name} 记录的是写死的字符串，
 *         与实际调用的模型无关。换成 DeepSeek 后，库里仍记录 {@code qwen-plus}。</li>
 *     <li><b>费用错误</b>：{@link LlmCostCalculatorImpl} 按该假模型名查费率表，
 *         等于用 A 厂商的单价结算 B 厂商的用量。</li>
 * </ol>
 *
 * <p>本类把「模型名」与「费率表」都外置为配置项，使二者与
 * {@code spring.ai.*} 中实际使用的模型保持一致。</p>
 *
 * <h3>配置示例</h3>
 * <pre>{@code
 * argus:
 *   rag:
 *     llm:
 *       chat-model: deepseek-flash
 *       pricing:
 *         deepseek-flash:
 *           input-per-1k: 0.002
 *           output-per-1k: 0.008
 * }</pre>
 *
 * @see LlmCostCalculatorImpl
 */
@ConfigurationProperties(prefix = "argus.rag.llm")
@Data
public class LlmProperties {

    /**
     * 实际用于对话/问答的模型名。
     * <p>该值会被写入 {@code qa_records.model_name} 与
     * {@code llm_usage_records.model_name}，并作为费率表的查询键。
     * 必须与 {@code spring.ai} 中配置的对话模型保持一致。</p>
     */
    private String chatModel = "qwen-plus";

    /**
     * 模型费率表：每千 token 单价（元）。
     * <p>键为模型名，值为输入/输出单价。未在此表或内置表中的模型，
     * 将回退到 {@link LlmCostCalculatorImpl} 的默认费率。</p>
     */
    private Map<String, ModelPrice> pricing = new LinkedHashMap<>();

    /**
     * 单个模型的费率。
     */
    @Data
    public static class ModelPrice {

        /** 每千 token 输入单价（元） */
        private BigDecimal inputPer1k;

        /** 每千 token 输出单价（元） */
        private BigDecimal outputPer1k;
    }
}
