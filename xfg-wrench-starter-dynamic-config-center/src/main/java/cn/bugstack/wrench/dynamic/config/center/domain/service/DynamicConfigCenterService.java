package cn.bugstack.wrench.dynamic.config.center.domain.service;

import cn.bugstack.wrench.dynamic.config.center.config.DynamicConfigCenterAutoConfig;
import cn.bugstack.wrench.dynamic.config.center.config.DynamicConfigCenterAutoProperties;
import cn.bugstack.wrench.dynamic.config.center.domain.model.valobj.AttributeVO;
import cn.bugstack.wrench.dynamic.config.center.types.annotations.DCCValue;
import cn.bugstack.wrench.dynamic.config.center.types.common.Constants;
import org.apache.commons.lang.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicConfigCenterService implements IDynamicConfigCenterService {

    // 日志对象：用于记录初始化和动态调整过程中的关键信息。
    private final Logger log = LoggerFactory.getLogger(DynamicConfigCenterAutoConfig.class);

    // 配置对象：承载 xfg.wrench.config.* 的配置，例如 system。
    private final DynamicConfigCenterAutoProperties properties;

    // Redisson 客户端：负责和 Redis 交互（读写配置、监听消息等）。
    private final RedissonClient redissonClient;

    // 运行时缓存：
    // key   = 业务配置键（如 test-system_downgradeSwitch）
    // value = 对应的 Bean 实例（该 Bean 上存在 @DCCValue 字段）
    // 目的：后续收到“配置变更消息”时，能快速定位并更新目标 Bean 字段值。
    private final Map<String, Object> dccBeanGroup = new ConcurrentHashMap<>();

    // 构造注入：由 Spring 创建该对象时，把依赖传进来。
    // 机制原因：依赖通过构造器固定下来，避免“对象先创建、字段后注入”带来的空指针窗口期。
    public DynamicConfigCenterService(DynamicConfigCenterAutoProperties properties, RedissonClient redissonClient) {
        this.properties = properties;
        this.redissonClient = redissonClient;
    }

    @Override
    public Object proxyObject(Object bean) {
        // 这个方法会在 Bean 初始化后被调用（见 BeanPostProcessor）。
        // 目标：扫描 Bean 字段，找到 @DCCValue，并把字段值从 Redis/默认值初始化进去。

        // 默认先按“普通 Bean”处理。
        Class<?> targetBeanClass = bean.getClass();
        Object targetBeanObject = bean;
        if (AopUtils.isAopProxy(bean)) {
            // 为什么代理对象必须“拆开”处理（追根溯源）：
            //
            // 1) Spring AOP 的本质是“包装”：
            //    容器里暴露出来的 bean 可能不是原始业务类实例，而是代理类实例。
            //    常见代理形态：
            //    - JDK 动态代理：生成实现接口的代理类（com.sun.proxy.$ProxyXX）
            //    - CGLIB 代理：生成目标类的子类（XXX$$EnhancerBySpringCGLIB）
            //
            // 2) 反射扫描字段依赖“类元数据”：
            //    getDeclaredFields() 只看“当前这个 Class 自己声明的字段”。
            //    若拿代理类去扫，得到的是代理类自己的字段结构（回调、拦截器等），
            //    而不是业务类里真正声明的字段，自然可能读不到 @DCCValue。
            //
            // 3) 反射赋值依赖“真实承载对象”：
            //    即使侥幸拿到了正确 Field，如果把值 set 到错误对象（代理壳）上，
            //    业务代码读取的真实目标对象字段可能并未改变，导致“看起来赋值了，实际没生效”。
            //
            // 所以这里要做两件事：
            // A. 用 AopUtils.getTargetClass(bean) 获取业务真实类（确保注解扫描基于真实字段定义）
            // B. 用 AopProxyUtils.getSingletonTarget(bean) 获取真实目标对象（确保字段赋值落在真实对象上）
            targetBeanClass = AopUtils.getTargetClass(bean);
            targetBeanObject = AopProxyUtils.getSingletonTarget(bean);
        }

        // 反射拿到当前类声明的所有字段（包含 private）。
        Field[] fields = targetBeanClass.getDeclaredFields();
        for (Field field : fields) {
            // 只处理带 @DCCValue 的字段；其他字段跳过。
            if (!field.isAnnotationPresent(DCCValue.class)) {
                continue;
            }

            // 读取注解对象本身，拿到注解参数 value。
            DCCValue dccValue = field.getAnnotation(DCCValue.class);

            // 约定格式：属性名:默认值，例如 downgradeSwitch:0
            String value = dccValue.value();
            if (StringUtils.isBlank(value)) {
                // 注解没写值，直接报错，避免静默失败。
                throw new RuntimeException(field.getName() + " @DCCValue is not config value config case 「isSwitch/isSwitch:1」");
            }

            // 以 ":" 切分注解值：前半段是属性名，后半段是默认值。
            String[] splits = value.split(Constants.SYMBOL_COLON);
            // 拼 Redis 业务 Key：system + "_" + 属性名
            // 例如 test-system + "_" + downgradeSwitch => test-system_downgradeSwitch
            String key = properties.getKey(splits[0].trim());

            // 三元表达式：
            // 若写了默认值（长度为2），取 splits[1]；否则为 null。
            String defaultValue = splits.length == 2 ? splits[1] : null;

            // 计划要写入字段的值，先默认用注解中的默认值。
            String setValue = defaultValue;

            try {
                // 不允许没有默认值：因为首次启动时 Redis 里可能还没有该 Key，
                // 没有默认值就无法初始化字段，业务行为会不可预期。
                if (StringUtils.isBlank(defaultValue)) {
                    throw new RuntimeException("dcc config error " + key + " is not null - 请配置默认值！");
                }

                // Redis 读取流程：
                // 1) 如果 Key 不存在：写入默认值（相当于初始化配置中心）
                // 2) 如果 Key 已存在：读取 Redis 最新值覆盖默认值
                RBucket<String> bucket = redissonClient.getBucket(key);
                boolean exists = bucket.isExists();
                if (!exists) {
                    bucket.set(defaultValue);
                } else {
                    setValue = bucket.get();
                }

                // 反射赋值流程：
                // private 字段默认不可写，先 setAccessible(true) 打开访问权限，
                // 写入后再恢复为 false。
                field.setAccessible(true);
                field.set(targetBeanObject, setValue);
                field.setAccessible(false);
            } catch (Exception e) {
                // 任何异常都上抛为运行时异常，阻止错误配置悄悄上线。
                throw new RuntimeException(e);
            }

            // 记录“这个配置键属于哪个 Bean”，用于后续动态变更时快速回写。
            // 机制原因：消息监听回调只知道“配置键和值”，并不知道要改哪个对象；
            // 先建立索引，回调阶段就能 O(1) 定位目标 Bean。
            dccBeanGroup.put(key, targetBeanObject);
        }

        // 返回原 Bean，保持 Spring 生命周期流程不变。
        return bean;
    }

    @Override
    public void adjustAttributeValue(AttributeVO attributeVO) {
        // 入口数据来自 Redis Topic 消息：attribute=字段名，value=新值。
        // 这里把“消息语义”转换为“系统内统一 key 语义”（system_attribute）。
        String key = properties.getKey(attributeVO.getAttribute());
        String value = attributeVO.getValue();

        // 先更新 Redis 存储层，再更新本地内存字段。
        // 机制原因：
        // 1) Redis 是共享事实源（source of truth），多实例最终都应对齐它；
        // 2) 若先改本地再改 Redis，Redis 写失败时会出现“本地成功、全局失败”的状态分裂。
        RBucket<String> bucket = redissonClient.getBucket(key);
        boolean exists = bucket.isExists();
        // key 不存在直接返回：
        // 该实现把“必须先初始化过的配置项”视为合法项，避免被任意消息创建脏配置。
        if (!exists) return;
        bucket.set(attributeVO.getValue());

        // 从索引表找到要被动态修改的 Bean。
        Object objBean = dccBeanGroup.get(key);
        // 找不到就返回：
        // 典型场景是当前实例未加载对应业务模块，或该字段不在本实例进程中。
        if (null == objBean) return;

        Class<?> objBeanClass = objBean.getClass();
        // 若是代理类，取目标类用于字段反射。
        // 机制原因同 proxyObject：getDeclaredField 依赖真实类字段元数据。
        if (AopUtils.isAopProxy(objBean)) {
            objBeanClass = AopUtils.getTargetClass(objBean);
        }

        try {
            // 用字段名精确定位目标字段（attribute 值必须与字段名一致）。
            // 机制原因：动态配置是“按字段名路由”的，这让协议简单，但也要求命名严格匹配。
            Field field = objBeanClass.getDeclaredField(attributeVO.getAttribute());
            // 私有字段默认不可写；反射写入前必须临时开放访问权限。
            field.setAccessible(true);
            // 把新值写入当前实例内存中的目标 Bean 字段，实现“热更新”。
            field.set(objBean, value);
            // 还原访问控制，降低后续误操作风险。
            field.setAccessible(false);

            log.info("DCC 节点监听，动态设置值 {} {}", key, value);

        } catch (Exception e) {
            // 抛出运行时异常而非吞掉异常：
            // 机制原因：动态配置失败属于关键一致性问题，静默失败会让线上行为与预期脱节且难排查。
            throw new RuntimeException(e);
        }
    }

}
