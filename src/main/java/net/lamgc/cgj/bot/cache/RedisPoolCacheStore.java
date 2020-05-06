package net.lamgc.cgj.bot.cache;

import com.google.common.base.Strings;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;

import java.net.URI;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class RedisPoolCacheStore<T> implements CacheStore<T> {

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private final Logger log;

    public RedisPoolCacheStore(URI redisServerUri, String prefix) {
        this(redisServerUri, null, 0, null, prefix);
    }

    public RedisPoolCacheStore(URI redisServerUri, JedisPoolConfig config, int timeout, String password, String prefix) {
        this(new JedisPool(config == null ? new GenericObjectPoolConfig<JedisPool>() : config, redisServerUri.getHost(),
                redisServerUri.getPort() == -1 ? 6379 : redisServerUri.getPort(),
                timeout <= 0 ? Protocol.DEFAULT_TIMEOUT : timeout, password),
                prefix
        );
    }

    public RedisPoolCacheStore(JedisPool pool, String keyPrefix) {
        jedisPool = Objects.requireNonNull(pool);
        if(jedisPool.isClosed()) {
            throw new IllegalStateException("JedisPool is closed");
        }
        log = LoggerFactory.getLogger(this.getClass().getName() + "@" + Integer.toHexString(jedisPool.hashCode()));
        if(!Strings.isNullOrEmpty(keyPrefix)) {
            this.keyPrefix = keyPrefix.endsWith(".") ? keyPrefix : keyPrefix + ".";
        } else {
            this.keyPrefix = "";
        }
    }

    @Override
    public void update(String key, T value, long expire) {
        update(key, value, expire <= 0 ? null : new Date(System.currentTimeMillis() + expire));
    }

    @Override
    public void update(String key, T value, Date expire) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(keyPrefix + key, parse(value));
            if(expire != null) {
                jedis.pexpireAt(keyPrefix + key, expire.getTime());
                log.debug("已设置Key {} 的过期时间(Expire: {})", key, expire.getTime());
            }
        }
    }

    @Override
    public T getCache(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return analysis(jedis.get(keyPrefix + key));
        }
    }

    @Override
    public boolean exists(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists(keyPrefix + key);
        }
    }

    @Override
    public boolean exists(String key, Date date) {
        return exists(key);
    }

    @Override
    public boolean clear() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.flushDB().equalsIgnoreCase("ok");
        }
    }

    @Override
    public Set<String> keys() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.keys(keyPrefix + "*");
        }
    }

    @Override
    public boolean remove(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.del(keyPrefix + key) == 1;
        }
    }

    /**
     * 转换方法
     * @param dataObj 原数据
     * @return 文本型数据
     */
    protected abstract String parse(T dataObj);

    /**
     * 将String数据转换成指定类型的对象
     * @param dataStr String数据
     * @return 泛型指定类型的对象
     */
    protected abstract T analysis(String dataStr);

    @Override
    public boolean supportedPersistence() {
        return true;
    }

    /**
     * 执行Jedis相关操作.
     * @param consumer 执行方法
     */
    protected void executeJedisCommand(Consumer<Jedis> consumer) {
        try (Jedis jedis = this.jedisPool.getResource()) {
            consumer.accept(jedis);
        }
    }

    /**
     * 执行Jedis相关操作.
     * @param function 执行方法
     * @param <R> 返回值类型
     * @return 返回提供Function函数式接口所返回的东西
     */
    protected <R> R executeJedisCommand(Function<Jedis, R> function) {
        try (Jedis jedis = this.jedisPool.getResource()) {
            return function.apply(jedis);
        }
    }

    @Override
    public T getCache(String key, long index, long length) {
        return getCache(key);
    }

    @Override
    public long length(String key) {
        return -1;
    }

    @Override
    public boolean supportedList() {
        return false;
    }

}
