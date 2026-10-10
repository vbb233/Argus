package com.argus.rag.metrics.config;

import com.argus.rag.metrics.cost.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 计量与计费模块配置。
 *
 * <p>注册 {@link LlmProperties}，使 {@code argus.rag.llm.*} 下的配置
 * 能够绑定到该属性类。与 {@code AuthConfiguration}、
 * {@code DocumentIngestionConfiguration} 的写法保持一致。</p>
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class MetricsConfiguration {
}
