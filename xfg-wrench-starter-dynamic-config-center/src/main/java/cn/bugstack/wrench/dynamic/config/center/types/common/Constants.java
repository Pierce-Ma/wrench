package cn.bugstack.wrench.dynamic.config.center.types.common;

/**
 * 常量定义类：
 * 1) 集中管理“不会变化”的字符串，避免魔法值散落在业务代码里。
 * 2) 统一命名规范，减少拼写错误带来的线上问题。
 */
public class Constants {

    /**
     * Redis Topic 前缀。
     * 完整 Topic 会由前缀 + 应用名拼接得到，例如：
     * DYNAMIC_CONFIG_CENTER_REDIS_TOPIC_test-system
     */
    public final static String DYNAMIC_CONFIG_CENTER_REDIS_TOPIC = "DYNAMIC_CONFIG_CENTER_REDIS_TOPIC_";

    /**
     * 冒号分隔符。
     * 用于解析类似 "downgradeSwitch:0" 这样的配置表达式。
     */
    public final static String SYMBOL_COLON = ":";

    /**
     * 下划线分隔符。
     * 用于拼接系统名和属性名，例如：test-system_downgradeSwitch。
     */
    public final static String LINE = "_";

    /**
     * 根据应用名生成 Redis Topic 名称。
     *
     * @param application 应用/系统名（如 test-system）
     * @return 完整 Topic 名（如 DYNAMIC_CONFIG_CENTER_REDIS_TOPIC_test-system）
     */
    public static String getTopic(String application) {
        // static 方法可通过类名直接调用：Constants.getTopic("test-system")
        return DYNAMIC_CONFIG_CENTER_REDIS_TOPIC + application;
    }

}
