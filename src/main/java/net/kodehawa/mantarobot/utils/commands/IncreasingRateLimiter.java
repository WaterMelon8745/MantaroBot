package net.kodehawa.mantarobot.utils.commands;

import net.dv8tion.jda.core.entities.User;
import org.apache.commons.io.IOUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class IncreasingRateLimiter {
    private static final String SCRIPT;

    private final JedisPool pool;
    private final String prefix;
    private final int limit;
    private final int cooldown;
    private final int spamBeforeCooldownIncrease;
    private final int cooldownIncrease;
    private final int maxCooldown;
    private String scriptSha;
    private boolean randomIncrement;

    static {
        try {
            SCRIPT = IOUtils.toString(IncreasingRateLimiter.class.getResourceAsStream("/ratelimiter.lua"), StandardCharsets.UTF_8);
        } catch(IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private IncreasingRateLimiter(JedisPool pool, String prefix, int limit, int cooldown, int spamBeforeCooldownIncrease, int cooldownIncrease, int maxCooldown, boolean randomIncrement) {
        this.pool = pool;
        this.prefix = prefix;
        this.limit = limit;
        this.cooldown = cooldown;
        this.spamBeforeCooldownIncrease = spamBeforeCooldownIncrease;
        this.cooldownIncrease = cooldownIncrease;
        this.maxCooldown = maxCooldown;
        this.randomIncrement = randomIncrement;
    }

    @SuppressWarnings("unchecked")
    private RateLimit limit0(String key) {
        try(Jedis j = pool.getResource()) {
            if(scriptSha == null) {
                scriptSha = j.scriptLoad(SCRIPT);
            }
            long start = Instant.now().toEpochMilli();
            List<Long> result;
            try {
                result = (List<Long>)j.evalsha(scriptSha,
                        Collections.singletonList(key),
                        Arrays.asList(
                                String.valueOf(limit),
                                String.valueOf(start),
                                String.valueOf(cooldown + (randomIncrement ? ThreadLocalRandom.current().nextInt(cooldown / 4) : 0)),
                                String.valueOf(spamBeforeCooldownIncrease),
                                String.valueOf(cooldownIncrease),
                                String.valueOf(maxCooldown)
                        )
                );
            } catch(JedisNoScriptException e) {
                //script not in cache. force load it and try again.
                scriptSha = j.scriptLoad(SCRIPT);
                return limit0(key);
            }

            return new RateLimit(
                    start,
                    (int)(limit - result.get(0)),
                    result.get(1) - start,
                    result.get(2).intValue()
            );
        }
    }

    public RateLimit limit(String key) {
        return limit0(prefix + key);
    }

    public long getRemaniningCooldown(User user) {
        try(Jedis j = pool.getResource()) {
            String resetAt = j.hget(prefix + user.getId(), "reset");
            if(resetAt == null) {
                return 0;
            }

            return Long.parseLong(resetAt) - System.currentTimeMillis();
        }
    }

    public static class Builder {
        private JedisPool pool;
        private String prefix = "";
        private int limit = -1;
        private int cooldown = -1;
        private int cooldownPenaltyIncrease;
        private int spamTolerance;
        private int maxCooldown;
        private boolean randomIncrement = true;

        public Builder pool(JedisPool pool) {
            this.pool = pool;
            return this;
        }

        public Builder randomIncrement(boolean incr) {
            this.randomIncrement = incr;
            return this;
        }

        public Builder prefix(String prefix) {
            if(prefix == null) {
                this.prefix = "";
            } else {
                this.prefix = prefix + ":";
            }
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder cooldown(int amount, TimeUnit unit) {
            int inMillis = (int)unit.toMillis(amount);
            if(inMillis < 1) {
                throw new IllegalArgumentException("Must be at least one millisecond!");
            }
            this.cooldown = inMillis;
            return this;
        }

        public Builder cooldownPenaltyIncrease(int amount, TimeUnit unit) {
            int inMillis = (int)unit.toMillis(amount);
            if(inMillis < 1) {
                throw new IllegalArgumentException("Must be at least one millisecond!");
            }
            this.cooldownPenaltyIncrease = inMillis;
            return this;
        }

        public Builder spamTolerance(int tolerance) {
            if(tolerance < 0) {
                throw new IllegalArgumentException("Must be 0 or positive");
            }
            this.spamTolerance = tolerance;
            return this;
        }

        public Builder maxCooldown(int amount, TimeUnit unit) {
            int inMillis = (int)unit.toMillis(amount);
            if(inMillis < cooldown) {
                throw new IllegalArgumentException("Must be greater than or equal to initial cooldown!");
            }
            this.maxCooldown = inMillis;
            return this;
        }

        public IncreasingRateLimiter build() {
            if(pool == null) {
                throw new IllegalStateException("Pool must be set");
            }
            if(limit < 0) {
                throw new IllegalStateException("Limit must be set");
            }
            if(cooldown < 0) {
                throw new IllegalStateException("Cooldown must be set");
            }
            return new IncreasingRateLimiter(pool, prefix, limit, cooldown, spamTolerance, cooldownPenaltyIncrease, maxCooldown, randomIncrement);
        }
    }
}
