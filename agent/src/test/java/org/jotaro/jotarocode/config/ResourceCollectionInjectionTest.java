package org.jotaro.jotarocode.config;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code @Resource} 在没有同名 Bean 时按类型收齐，工具列表和模型表都靠这个。 */
class ResourceCollectionInjectionTest {

    @Test
    void collectsEveryBeanOfTheDeclaredType() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                Holder.class, First.class, Second.class)) {
            assertEquals(2, context.getBean(Holder.class).items.size());
        }
    }

    @Component
    static class Holder {
        @Resource
        private List<Item> items;
    }

    interface Item {
    }

    @Component
    static class First implements Item {
    }

    @Component
    static class Second implements Item {
    }
}
