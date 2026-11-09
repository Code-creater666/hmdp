package com.hmdp.utils;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造器注入redis
     * @param stringRedisTemplate
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将对象存入redis并设置过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);

    }

    /**
     * 将对象存入Redis并设置逻辑过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));//转成秒
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),time,unit);

    }

    /**
     * 防止缓存穿透的根据id查询数据
     * @param keyPrefix
     * @param id
     * @param type
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrouth(String keyPrefix, ID id, Class<R> type, Function<ID,R> deFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;
        //1.从Redis中查缓存数据
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.查到数据返回信息
        if (StrUtil.isNotBlank(json)){
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //3.查到空字符串返回防穿透,因为上面blank只能过滤有字符的情况，只要排除null就能确定命中空字符串
        if (json != null){
            return null;
        }
        //4.查数据库,函数式编程，需要调用者传入一个函数逻辑
        R r = deFallback.apply(id);

        //5.mysql查不到存空字符串防穿透
        if (r==null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.查到了存Redis
        this.set(key,r,time,unit);

        //7.返回
        return r;
    }

    /**
     * 线程池
     */
    public static final ExecutorService CACHE_REBUILD = Executors.newFixedThreadPool(10);

    /**
     * 封装缓存击穿不含缓存穿透（基于逻辑过期实现）热点key问题，仅适用于查询热点数据
     * @param id
     * @return
     */
    public  <R,ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallback,
            Long time,
            TimeUnit unit){
        String key = keyPrefix + id;
        //1.从Redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断数据是否存在
        if (StrUtil.isBlank(json)){
            //不存在说明不是热点key
            return null;
        }
        //3.数据存在先反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);  //直接取object
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期直接返回信息
            return r;
        }
        //5.过期缓存重建
        //5.1获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean islock = trylock(lockKey);
        //5.2判断是否获取成功
        if (islock){
            //5.3成功开启独立线程重建缓存
            CACHE_REBUILD.submit(()->{
                try {
                    //再次查缓存
                    String latestJson = stringRedisTemplate.opsForValue().get(key);

                    if (StrUtil.isNotBlank(latestJson)) {
                        RedisData latest = JSONUtil.toBean(latestJson, RedisData.class);
                        if (latest.getExpireTime().isAfter(LocalDateTime.now())) {
                            return; // 已被其他请求刷新，仍会执行 finally
                        }
                    }
                    //缓存重建
                    R r1 = dbFallback.apply(id);//查数据库
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //5.4失败返回过期的信息
        return r;
    }


    /**
     * 封装缓存击穿含缓存穿透（基于互斥锁实现）
     * @param id
     * @return
     */
    public  <R,ID> R queryWithMutex(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallback,
            Long time,
            TimeUnit unit){

        String key = keyPrefix + id;
        String lockKey = lockKeyPrefix+id;

        while (true) {
            //1.查缓存
            String json = stringRedisTemplate.opsForValue().get(key);  //观察缓存数据
            //2.查到数据返回信息
            if (StrUtil.isNotBlank(json)){
                R r = JSONUtil.toBean(json, type);
                return r;
            }
            //3.查到空字符串返回错误信息防穿透
            if (json !=null){
                return null;
            }                                                     //判断缓存是否命中
            //4.缓存未命中重建互斥锁
            //4.1获取互斥锁
            boolean isLock = trylock(lockKey);
            //4.2判断是否成功
            if (!isLock){
                try {
                    //失败则重试
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException("被打断");
                }
                continue;//重试
            }

            try {
                //5.必须成功拿锁才能进这个try

                //必须再查一次缓存看数据有没有更新好
                json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json)) {
                    return JSONUtil.toBean(json, type);
                }
                if (json != null) {
                    return null;
                }
                //查数据库
                R r = dbFallback.apply(id);                             //行动：未命中查数据库
                //5.mysql查不到存空字符串防穿透
                if (r==null){
                    stringRedisTemplate.opsForValue().set(key,"",2, TimeUnit.MINUTES);
                    return null;
                }
                //6.查到了存Redis
                this.set(key,r,time,unit);
                return r;
            } finally {
                //7.释放锁
                unlock(lockKey);
            }
        }
    }



    /**
     * 定义锁
     * @param key
     * @return
     */
    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
