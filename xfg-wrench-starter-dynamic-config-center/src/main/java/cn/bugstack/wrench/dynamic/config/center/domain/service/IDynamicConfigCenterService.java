package cn.bugstack.wrench.dynamic.config.center.domain.service;

import cn.bugstack.wrench.dynamic.config.center.domain.model.valobj.AttributeVO;

/**
 * 动态配置中心服务接口
 * @author Fuzhengwei bugstack.cn @小傅哥
 * 2025-04-19 09:54
 */
public interface IDynamicConfigCenterService {

    Object proxyObject(Object bean);

    /**
     * 调整属性值
     */
    void adjustAttributeValue(AttributeVO attributeVO);

}
