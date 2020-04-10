package net.lamgc.cgj.bot.cache;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import redis.clients.jedis.JedisPool;

import java.net.URI;

public class JsonRedisCacheStore extends RedisPoolCacheStore<JsonElement> {

    private final Gson gson;

    public JsonRedisCacheStore(URI redisServerUri, String prefix, Gson gson) {
        super(redisServerUri, prefix);
        this.gson = gson;
    }

    public JsonRedisCacheStore(JedisPool jedisPool, String prefix, Gson gson) {
        super(jedisPool, prefix);
        this.gson = gson;
    }

    @Override
    protected String parse(JsonElement data) {
        return this.gson.toJson(data);
    }

    @Override
    protected JsonElement analysis(String dataStr) {
        return this.gson.fromJson(dataStr, JsonElement.class);
    }
}
