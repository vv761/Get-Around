package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public  CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dfFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            //返回一个错误信息
            return null;
        }

        //不存在，根据id查询数据库
        R r = dfFallback.apply(id);

        //不存在返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //存在，写入redis
        this.set(key,r,time,unit);

        //返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(String keyPrefix ,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if (StrUtil.isBlank(json)) {
            //未命中，直接返回
            return null;
        }
        //命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return r;
        }


        //已过期，需要缓存重建

        //缓存重建
        //获取互斥锁
        String lockKey =LOCK_SHOP_KEY+ id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        if (isLock){
            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //返回过期的店铺信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
