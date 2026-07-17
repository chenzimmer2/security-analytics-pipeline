package com.akamai.miniwsa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Exposes a {@link StringRedisTemplate} bean for raw string operations.
 *
 * {@code StringRedisTemplate} uses String serializers for both key and value,
 * which is exactly what the rate-limit filter needs (plain INCR / EXPIRE on
 * string keys — no JSON serialization overhead).
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
