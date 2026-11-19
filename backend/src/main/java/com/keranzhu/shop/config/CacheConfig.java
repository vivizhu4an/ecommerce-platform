package com.keranzhu.shop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keranzhu.shop.catalog.ProductCacheEvictor;
import com.keranzhu.shop.catalog.ProductView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration
public class CacheConfig {

    /**
     * Products are stored as plain JSON with a short TTL. The TTL is a safety net:
     * entries are normally evicted right after the write that changes them commits.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer productCacheCustomizer(ObjectMapper mapper,
                                                             @Value("${app.cache.product-ttl}") Duration ttl) {
        var serializer = new Jackson2JsonRedisSerializer<>(mapper, ProductView.class);
        return builder -> builder.withCacheConfiguration(ProductCacheEvictor.CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(ttl)
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(serializer)));
    }
}
