package io.dargent.api.config;

import io.dargent.payments.adapter.out.cache.RedisReplayCacheClient;
import io.dargent.payments.adapter.out.cache.ReplayCacheClient;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * M5 S2 (D3): the Redis client behind the idempotent-replay cache. Conditionally active ONLY when
 * {@code dargent.cache.redis.enabled=true} — the carved default (false) creates zero Redis beans, so
 * the app runs DB-direct and Boot's Spring Data Redis auto-config stays excluded (application.yaml).
 *
 * <p>Command timeout is capped at 1s so a dead cache degrades to the DB fallback fast (the fail-open
 * IT stops Redis mid-test); the cache must never be allowed to lag the money path (STOP 3).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dargent.cache.redis.enabled", havingValue = "true")
class CacheConfiguration {

    private static final Duration CACHE_COMMAND_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    RedisConnectionFactory cacheRedisConnectionFactory(@Value("${dargent.cache.redis.uri}") String uri) {
        RedisStandaloneConfiguration config = standaloneFromUri(URI.create(uri));
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(CACHE_COMMAND_TIMEOUT)
                .build();
        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    ReplayCacheClient replayCacheClient(StringRedisTemplate redis) {
        return new RedisReplayCacheClient(redis);
    }

    private static RedisStandaloneConfiguration standaloneFromUri(URI uri) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getUserInfo() != null) {
            String[] userInfo = uri.getUserInfo().split(":", 2);
            config.setUsername(userInfo[0]);
            if (userInfo.length > 1) {
                config.setPassword(RedisPassword.of(userInfo[1]));
            }
        }
        return config;
    }
}
