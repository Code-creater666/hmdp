package com.hmdp.config;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties){
        // 配置
        Config config = new Config();
        String scheme = redisProperties.isSsl() ? "rediss://" : "redis://";
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(scheme + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        String password = redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            serverConfig.setPassword(password);
        }
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
