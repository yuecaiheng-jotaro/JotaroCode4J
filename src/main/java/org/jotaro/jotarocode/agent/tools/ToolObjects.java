package org.jotaro.jotarocode.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 启动完成后收集带 {@link Tool} 方法的 Bean。新增一个 {@code @Component} 并写上 {@code @Tool} 方法就会进入会话，
 * 不需要再登记名字或类型。
 */
@Component
public class ToolObjects {

    private final ApplicationContext context;
    private volatile List<Object> tools;

    public ToolObjects(ApplicationContext context) {
        this.context = context;
    }

    public List<Object> all() {
        List<Object> cached = tools;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (tools == null) {
                tools = List.copyOf(context.getBeansOfType(Object.class).values().stream()
                        .filter(ToolObjects::exposesTool)
                        .toList());
            }
            return tools;
        }
    }

    static boolean exposesTool(Object bean) {
        Class<?> type = bean.getClass();
        if (type.getName().contains("$$")) {
            type = type.getSuperclass();
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }
}
