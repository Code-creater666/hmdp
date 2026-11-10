package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将java对象存入Redis，并设置过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);   //疑问，这里为什么不需要传入对象类型
    }

    /**
     * 将java对象存入Redis，并设置逻辑过期时间
     */
    public void setWithExpireTime(String key, Object value, Long expireTime, TimeUnit unit){
        //1.封装RedisData对象加入逻辑时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)));
        //2.将RedisData存入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据id查询信息，同时防止缓存穿透
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallback,
            Long time,
            TimeUnit unit){

        //1.查询redis
        String key = keyPrefix + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //2.判断数据是否存在
        if (StrUtil.isNotBlank(jsonStr)){
            //3.存在直接返回
            return JSONUtil.toBean(jsonStr,type);
        }
        //4.不存在则可能是空或者空字符，查空字符串防穿透
        if (jsonStr!=null){                                                 //判断
            return null;
        }
        //5.真不存在查数据库
        R r = dbFallback.apply(id);
        //6.判断数据库是否存在，存在存入redis并且返回数据
        if (r!=null){
            this.set(key,r,time,unit);
            return r;
        }
        //7.不存在存空串防击穿
        stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);    //行动改变了前面的判断
        return null;
    }

    /**
     * 根据id查商铺信息，同时防止缓存击穿（基于互斥锁）
     */
    public <R,ID> R queryWithMutex(
            String lockKeyPrefix,
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallback,
            Long time,
            TimeUnit unit){

        while (true) {
            //1.查询缓存
            String key = keyPrefix + id;
            String jsonStr = stringRedisTemplate.opsForValue().get(key);
            //2.判断是否命中
            if (StrUtil.isNotBlank(jsonStr)){                          //判断
                //3.命中直接返回
                return JSONUtil.toBean(jsonStr,type);
            }
            //4.未命中缓存重建
            String lockKey = lockKeyPrefix + id;
            //4.1上互斥锁
            Boolean islock = tryLock(lockKey);
            //4.2成功则查数据库重建返回数据
            if (islock){
                try {
                    // 拿锁后，二次判断
                    jsonStr = stringRedisTemplate.opsForValue().get(key);

                    if (StrUtil.isNotBlank(jsonStr)) {
                        return JSONUtil.toBean(jsonStr, type);
                    }

                    R r = dbFallback.apply(id);
                    this.set(key,r,time,unit);                     //执行
                    return r;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            }
            //4.3不成功循环等待
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 线程池
     */
    public static final ExecutorService CACHE_REBUILD = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询店铺信息，同时避免缓存击穿，（基于逻辑过期）
     */
    public <R,ID> R queryWithLogical(String lockKeyPrefix,String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        //1.查询缓存
        String key = keyPrefix + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //2.没查到不是热点数据直接返回null
        if (StrUtil.isBlank(jsonStr)){
            return null;
        }
        //3.查到判断是否过期
        //3.1反序列化为对象
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        JSONObject jsonObject = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(jsonObject, type);
        //3.2拿逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){                    //判断
            //4.没过期直接返回
            return r;
        }
        //5.过期缓存重建
        //5.1获取互斥锁
        String lockkey = lockKeyPrefix + id;
        Boolean islock = tryLock(lockkey);
        if(islock){
            //5.2成功则交给独立线程重建
            CACHE_REBUILD.submit(()->{
                try {
                    //再次判断
                    String jsonStr2 = stringRedisTemplate.opsForValue().get(key);
                    RedisData redisData2 = JSONUtil.toBean(jsonStr2, RedisData.class);
                    LocalDateTime expireTime2 = redisData2.getExpireTime();
                    if (expireTime2.isAfter(LocalDateTime.now())){
                        return;
                    }
                    //查数据库
                    R r1 = dbFallback.apply(id);
                    this.setWithExpireTime(key,r1,time,unit);                    //执行
                } finally {
                    unLock(lockkey);
                }
            });
        }
        //5.3失败则返回过期数据
        return r;
    }



    /**
     * 上锁
     * @param lockKey
     * @return
     */
    private Boolean tryLock(String lockKey){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 解锁
     * @param lockKey
     */
    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
    }

}
