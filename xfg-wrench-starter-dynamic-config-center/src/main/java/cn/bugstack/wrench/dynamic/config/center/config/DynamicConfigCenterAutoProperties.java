package cn.bugstack.wrench.dynamic.config.center.config;

import cn.bugstack.wrench.dynamic.config.center.types.common.Constants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 动态配置中心配置类：
 * 1) 通过 @ConfigurationProperties 把 yml/properties 中以 xfg.wrench.config 开头的配置绑定到当前对象。
 * 2) Spring 容器里其他 Bean 可注入该对象，统一读取配置值。
 */
@ConfigurationProperties(prefix = "xfg.wrench.config", ignoreInvalidFields = true)
public class DynamicConfigCenterAutoProperties {

    /**
     * system 对应配置项 xfg.wrench.config.system
     * 例如：xfg.wrench.config.system=test-system
     */
    private String system;

    /**
     * 根据“系统名 + 下划线 + 属性名”拼接出 Redis 使用的实际 Key。
     * 例如：system=test-system，attributeName=downgradeSwitch
     * 结果：test-system_downgradeSwitch
     */
    public String getKey(String attributeName) {
        // this.system：当前对象字段，Constants.LINE：常量 "_"。
        return this.system + Constants.LINE + attributeName;
    }

    /**
     * JavaBean getter：
     * Spring 通过 getter/setter 进行属性访问与绑定。
     */
    public String getSystem() {
        return system;
    }

    /**
     * JavaBean setter：
     * Spring 在配置绑定时会调用 setSystem(...) 给字段赋值。
     */
    public void setSystem(String system) {
        // 参数 system 是方法入参，this.system 指当前对象成员变量，用 this 区分同名变量。
        this.system = system;
    }

}
