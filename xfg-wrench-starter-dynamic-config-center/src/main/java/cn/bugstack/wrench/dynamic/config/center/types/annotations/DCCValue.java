package cn.bugstack.wrench.dynamic.config.center.types.annotations;

import java.lang.annotation.*;

/**
 * 自定义注解：动态配置中心字段标记。
 * 作用：标记哪些字段需要被动态配置中心接管，并提供“配置项 + 默认值”表达式。
 * 示例：@DCCValue("downgradeSwitch:0")
 */
// 保留策略：运行时仍然保留该注解，便于反射读取（本项目在 Bean 初始化后扫描该注解）。
@Retention(RetentionPolicy.RUNTIME)
// 作用目标：可标注在字段、方法上。
@Target({ElementType.FIELD, ElementType.METHOD})
// 生成 JavaDoc 时会包含该注解信息，提升文档可读性。
@Documented
public @interface DCCValue {

    /**
     * 注解参数（默认参数名为 value，可简写）：
     * 约定格式为 "属性名:默认值"，例如 "downgradeSwitch:0"。
     * default "" 表示当未传值时，默认是空字符串。
     */
    String value() default "";

}
