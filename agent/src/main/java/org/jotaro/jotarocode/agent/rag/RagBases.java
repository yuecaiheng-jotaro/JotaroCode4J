package org.jotaro.jotarocode.agent.rag;

import org.jotaro.jotarocode.config.JotaroAgentProperties;

import java.util.List;

/** 知识库名单：不配就不使用，写 ALL 才查全部，写具体库名则只能查这些库。 */
public final class RagBases {

    public static final String ALL = "ALL";

    private RagBases() {
    }

    public record Choice(String knowledgeBase, String message) {
        public boolean ready() {
            return message == null;
        }
    }

    public static boolean enabled(List<JotaroAgentProperties.KnowledgeBase> bases) {
        return !named(bases).isEmpty();
    }

    public static Choice select(List<JotaroAgentProperties.KnowledgeBase> bases, String requested) {
        List<JotaroAgentProperties.KnowledgeBase> named = named(bases);
        String name = requested == null ? "" : requested.trim();
        if (named.isEmpty()) {
            return new Choice("", "知识库未启用。");
        }
        if (allowsAll(named)) {
            if (name.isEmpty() || ALL.equals(name)) {
                return new Choice("", null);
            }
            return new Choice(name, null);
        }
        List<JotaroAgentProperties.KnowledgeBase> concrete = concrete(named);
        if (name.isEmpty() && concrete.size() == 1) {
            return new Choice(concrete.get(0).getName().trim(), null);
        }
        if (name.isEmpty() || concrete.stream().noneMatch(base -> base.getName().trim().equals(name))) {
            String reason = name.isEmpty()
                    ? "请指定 knowledgeBase。"
                    : "知识库 " + name + " 不在可查询范围内。";
            return new Choice("", reason + "可选：" + catalog(concrete));
        }
        return new Choice(name, null);
    }

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

    private static boolean allowsAll(List<JotaroAgentProperties.KnowledgeBase> named) {
        return named.stream().anyMatch(base -> ALL.equals(base.getName().trim()));
    }

    private static List<JotaroAgentProperties.KnowledgeBase> concrete(List<JotaroAgentProperties.KnowledgeBase> named) {
        return named.stream().filter(base -> !ALL.equals(base.getName().trim())).toList();
    }

    private static List<JotaroAgentProperties.KnowledgeBase> named(List<JotaroAgentProperties.KnowledgeBase> bases) {
        if (bases == null || bases.isEmpty()) {
            return List.of();
        }
        return bases.stream()
                .filter(base -> base != null && base.getName() != null && !base.getName().isBlank())
                .toList();
    }

    private static String catalog(List<JotaroAgentProperties.KnowledgeBase> named) {
        return String.join("、", named.stream().map(base -> base.getName().trim()).toList());
    }
}
