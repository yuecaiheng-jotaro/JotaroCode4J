package org.jotaro.jotarocode.agent.rag;

import org.jotaro.jotarocode.config.JotaroAgentProperties;

import java.util.List;

/**
 * 知识库范围校验与系统提示拼接工具。
 *
 * <p>核心策略：
 * <ul>
 *   <li>未配置任何有效知识库时，禁用 RAG 检索能力。</li>
 *   <li>配置中包含 {@code ALL} 时，允许模型查询所有知识库，{@code knowledgeBase} 参数可省略。</li>
 *   <li>仅配置具体知识库名称时，模型只能在白名单范围内查询；若仅配置了 1 个具体库，未传参时会自动默认选中该库。</li>
 * </ul>
 */
public final class RagBases {

    /** 表示允许查询全部知识库的特殊标识。 */
    public static final String ALL = "ALL";

    private RagBases() {
    }

    /**
     * 知识库选择结果。
     *
     * @param knowledgeBase 最终解析出的知识库名称（为空字符串代表检索全部知识库或未命中）
     * @param message       校验失败时的提示信息；若为 {@code null} 则表示校验通过、可继续发起检索
     */
    public record Choice(String knowledgeBase, String message) {
        /**
         * 判断当前知识库选择是否通过校验。
         *
         * @return {@code true} 表示参数合法、可继续调用检索接口
         */
        public boolean ready() {
            return message == null;
        }
    }

    /**
     * 判断当前配置是否启用了知识库功能。
     *
     * @param bases 配置的知识库列表
     * @return 至少包含一个非空名称的知识库配置时返回 {@code true}
     */
    public static boolean enabled(List<JotaroAgentProperties.KnowledgeBase> bases) {
        return !named(bases).isEmpty();
    }

    /**
     * 根据配置的白名单校验并解析模型传入的目标知识库名称。
     *
     * @param bases     配置的知识库列表
     * @param requested 模型调用工具时请求查询的知识库名称（可为空）
     * @return 解析结果 {@link Choice}，包含最终使用的库名或拒绝原因
     */
    public static Choice select(List<JotaroAgentProperties.KnowledgeBase> bases, String requested) {
        List<JotaroAgentProperties.KnowledgeBase> named = named(bases);
        String name = requested == null ? "" : requested.trim();
        if (named.isEmpty()) {
            return new Choice("", "知识库未启用。");
        }
        // 配置了 ALL 通配符：不传参或显式传 ALL 时查询全量库，传具体库名时按指定库查询
        if (allowsAll(named)) {
            if (name.isEmpty() || ALL.equals(name)) {
                return new Choice("", null);
            }
            return new Choice(name, null);
        }
        List<JotaroAgentProperties.KnowledgeBase> concrete = concrete(named);
        // 仅配置了唯一一个具体知识库且模型未显式传参时，自动回退到该知识库
        if (name.isEmpty() && concrete.size() == 1) {
            return new Choice(concrete.get(0).getName().trim(), null);
        }
        // 校验请求的知识库是否在允许的具体名单内
        if (name.isEmpty() || concrete.stream().noneMatch(base -> base.getName().trim().equals(name))) {
            String reason = name.isEmpty()
                    ? "请指定 knowledgeBase。"
                    : "知识库 " + name + " 不在可查询范围内。";
            return new Choice("", reason + "可选：" + catalog(concrete));
        }
        return new Choice(name, null);
    }

    /**
     * 生成注入到系统提示词（System Prompt）中的知识库使用说明。
     *
     * @param bases 配置的知识库列表
     * @return 供模型阅读的知识库列表与调用规则说明；未启用时返回空字符串
     */
    public static String prompt(List<JotaroAgentProperties.KnowledgeBase> bases) {
        List<JotaroAgentProperties.KnowledgeBase> named = named(bases);
        if (named.isEmpty()) {
            return "";
        }
        if (allowsAll(named)) {
            return "可以查询全部知识库。需要资料时调用 rag，knowledgeBase 可以不传。";
        }
        List<JotaroAgentProperties.KnowledgeBase> concrete = concrete(named);
        StringBuilder builder = new StringBuilder("可查询的知识库：");
        for (JotaroAgentProperties.KnowledgeBase base : concrete) {
            builder.append("\n- ").append(base.getName().trim());
            if (base.getDescription() != null && !base.getDescription().isBlank()) {
                builder.append("：").append(base.getDescription().strip());
            }
        }
        builder.append("\n需要资料时调用 rag，并传入对应的 knowledgeBase。");
        if (concrete.size() > 1) {
            builder.append("配置了多个库时必须选其中一个。");
        }
        return builder.toString();
    }

    /** 检查有效配置列表中是否包含 {@code ALL} 通配项。 */
    private static boolean allowsAll(List<JotaroAgentProperties.KnowledgeBase> named) {
        return named.stream().anyMatch(base -> ALL.equals(base.getName().trim()));
    }

    /** 过滤掉 {@code ALL} 通配项，只保留具体的知识库配置。 */
    private static List<JotaroAgentProperties.KnowledgeBase> concrete(List<JotaroAgentProperties.KnowledgeBase> named) {
        return named.stream().filter(base -> !ALL.equals(base.getName().trim())).toList();
    }

    /** 过滤掉空对象及名称为空白的无效知识库配置。 */
    private static List<JotaroAgentProperties.KnowledgeBase> named(List<JotaroAgentProperties.KnowledgeBase> bases) {
        if (bases == null || bases.isEmpty()) {
            return List.of();
        }
        return bases.stream()
                .filter(base -> base != null && base.getName() != null && !base.getName().isBlank())
                .toList();
    }

    /** 将知识库名称列表拼接为顿号分隔的可读字符串。 */
    private static String catalog(List<JotaroAgentProperties.KnowledgeBase> named) {
        return String.join("、", named.stream().map(base -> base.getName().trim()).toList());
    }
}
