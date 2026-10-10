package com.argus.rag.metrics.cost;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于费率的 LLM 费用计算器。
 *
 * <h3>费率来源与优先级</h3>
 * <ol>
 *     <li><b>内置兜底费率</b>：历史版本写死的通义千问系列价格，仅用于保证
 *         配置缺失时的行为与旧版本一致。</li>
 *     <li><b>配置费率</b>（{@code argus.rag.llm.pricing.*}）：优先级更高，
 *         会覆盖内置表中同名的模型。</li>
 * </ol>
 *
 * <p>当请求的模型在两个表中都不存在时，会回退到
 * {@link #DEFAULT_INPUT_PRICE}/{@link #DEFAULT_OUTPUT_PRICE}，
 * <b>并打印 WARN 日志</b>——避免像历史版本那样静默地用错误单价结算。</p>
 *
 * @see LlmProperties
 */
@Component
@Slf4j
public class LlmCostCalculatorImpl implements LlmCostCalculator {

    /** 每千 token 默认费率（元），仅在模型既不在内置表也不在配置表中时使用 */
    private static final BigDecimal DEFAULT_INPUT_PRICE = new BigDecimal("0.0008");
    private static final BigDecimal DEFAULT_OUTPUT_PRICE = new BigDecimal("0.002");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    /**
     * 模型费率表：每千 token 的费用（元）
     * key: modelName, value: [inputPricePerKToken, outputPricePerKToken]
     */
    private final Map<String, BigDecimal[]> modelPricing = new ConcurrentHashMap<>();

    /**
     * 构造函数：先装载内置兜底费率，再用配置费率覆盖。
     *
     * @param properties LLM 计量计费配置
     */
    public LlmCostCalculatorImpl(LlmProperties properties) {
        // 内置兜底费率（与历史版本保持一致，避免配置缺失时行为变化）
        modelPricing.put("qwen-plus", new BigDecimal[]{
                new BigDecimal("0.0008"), new BigDecimal("0.002")
        });
        modelPricing.put("qwen-turbo", new BigDecimal[]{
                new BigDecimal("0.0003"), new BigDecimal("0.0006")
        });
        modelPricing.put("qwen-max", new BigDecimal[]{
                new BigDecimal("0.002"), new BigDecimal("0.006")
        });

        // 配置费率覆盖内置值
        if (properties != null && properties.getPricing() != null) {
            properties.getPricing().forEach((modelName, price) -> {
                if (modelName == null || price == null
                        || price.getInputPer1k() == null || price.getOutputPer1k() == null) {
                    log.warn("忽略不完整的费率配置: model={}, price={}", modelName, price);
                    return;
                }
                modelPricing.put(modelName, new BigDecimal[]{
                        price.getInputPer1k(), price.getOutputPer1k()
                });
            });
        }

        log.info("LLM 费率表初始化完成，共 {} 个模型: {}", modelPricing.size(), modelPricing.keySet());
    }

    /**
     * 计算单次调用的费用。
     *
     * @param modelName        模型名称
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @return 费用（元），保留 6 位小数
     */
    @Override
    public BigDecimal calculate(String modelName, int promptTokens, int completionTokens) {
        BigDecimal[] pricing = modelPricing.get(modelName);
        if (pricing == null) {
            // 显式告警，避免静默按错误单价结算
            log.warn("模型 {} 不在费率表中，将使用默认费率（输入 {}/千token，输出 {}/千token）。"
                            + "请在 argus.rag.llm.pricing 中补充该模型的真实单价。",
                    modelName, DEFAULT_INPUT_PRICE, DEFAULT_OUTPUT_PRICE);
            pricing = new BigDecimal[]{DEFAULT_INPUT_PRICE, DEFAULT_OUTPUT_PRICE};
        }

        BigDecimal inputCost = pricing[0]
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);

        BigDecimal outputCost = pricing[1]
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }
}
