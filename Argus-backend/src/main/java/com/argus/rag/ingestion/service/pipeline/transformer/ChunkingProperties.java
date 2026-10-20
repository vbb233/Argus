package com.argus.rag.ingestion.service.pipeline.transformer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档分块配置属性，绑定前缀 {@code ingestion.chunking}。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ingestion.chunking")
@Data
public class ChunkingProperties {

    /** 目标 token 数 */
    private int targetTokens = 500;
    /** 最大 token 数 */
    private int maxTokens = 800;
    /** 重叠 token 数 */
    private int overlapTokens = 80;
    /**
     * 是否为表格数据行额外生成「行级分块」。
     *
     * <p>表格切片后，一行数据往往淹没在几百字的混合分块里，词密度极低，
     * 检索时容易被大段叙述文字挤出候选集（实测「软件工程在第几学期开课」
     * 命中的全是专业介绍，而含目标行的分块排在第 13 位、从未进入 top-5）。</p>
     *
     * <p>行级分块把「表名 + 表头 + 一行数据」单独成块，让课程名与列名
     * 在同一小块内同时出现，显著提升召回。默认开启。</p>
     */
    private boolean tableRowChunks = true;
    /** 行级分块的数量上限，防止超大表格把分块数（进而把成本）撑爆 */
    private int maxTableRowChunks = 500;

    /** 使用默认值构造。 */
    public ChunkingProperties() {
    }

    /**
     * @param targetTokens  目标 token 数
     * @param maxTokens     最大 token 数
     * @param overlapTokens 重叠 token 数
     */
    public ChunkingProperties(int targetTokens, int maxTokens, int overlapTokens) {
        this.targetTokens = targetTokens;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }
}
