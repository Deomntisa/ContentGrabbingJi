package net.lamgc.cgj.bot.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Objects;
import java.util.Random;

/**
 * 具有继承性的热点数据缓存库
 * @param <T> 存储类型
 * @author LamGC
 */
public class HotDataCacheStore<T> implements CacheStore<T> {

    private final CacheStore<T> parent;
    private final CacheStore<T> current;
    private final long expireTime;
    private final int expireFloatRange;
    private final Random random = new Random();
    private final Logger log = LoggerFactory.getLogger(HotDataCacheStore.class.getSimpleName() + "@" + Integer.toHexString(this.hashCode()));

    /**
     * 构造热点缓存存储对象
     * @param parent 上级缓存存储库
     * @param current 热点缓存存储库, 最好使用本地缓存(例如 {@linkplain LocalHashCacheStore LocalHashCacheStore})
     * @param expireTime 本地缓存库的缓存项过期时间,
     *                   该时间并不是所有缓存项的最终过期时间, 还需要根据expireFloatRange的设定随机设置, 公式:
     *                   {@code expireTime + new Random().nextInt(expireFloatRange)}
     * @param expireFloatRange 过期时间的浮动范围, 用于防止短时间内大量缓存项失效导致的缓存雪崩
     */
    public HotDataCacheStore(CacheStore<T> parent, CacheStore<T> current, long expireTime, int expireFloatRange) {
        this.parent = parent;
        this.current = current;
        this.expireTime = expireTime;
        this.expireFloatRange = expireFloatRange;
        log.debug("HotDataCacheStore初始化完成. (Parent: {}, Current: {}, expireTime: {}, expireFloatRange: {})",
                parent, current, expireTime, expireFloatRange);
    }

    @Override
    public void update(String key, T value, long expire) {
        update(key, value, expire <= 0 ? null : new Date(System.currentTimeMillis() + expire));
    }

    @Override
    public void update(String key, T value, Date expire) {
        parent.update(key, value, expire);
        current.update(key, value, expire);
    }

    @Override
    public T getCache(String key) {
        if(!exists(key)) {
            log.debug("查询缓存键名不存在, 直接返回null.");
            return null;
        }
        T result = current.getCache(key);
        if(Objects.isNull(result)) {
            log.debug("Current缓存库未命中, 查询Parent缓存库");
            T parentResult = parent.getCache(key);
            if(Objects.isNull(parentResult)) {
                log.debug("Parent缓存库未命中, 缓存不存在");
                return null;
            }
            log.debug("Parent缓存命中, 正在更新Current缓存库...");
            current.update(key, parentResult, expireTime + random.nextInt(expireFloatRange));
            log.debug("Current缓存库更新完成.");
            result = parentResult;
        } else {
            log.debug("Current缓存库缓存命中.");
        }
        return result;
    }

    @Override
    public boolean exists(String key) {
        return current.exists(key) || parent.exists(key);
    }

    @Override
    public boolean exists(String key, Date date) {
        return current.exists(key, date) || parent.exists(key, date);
    }

    @Override
    public boolean clear() {
        return current.clear();
    }

    @Override
    public boolean supportedPersistence() {
        return current.supportedPersistence() || parent.supportedPersistence();
    }
}
