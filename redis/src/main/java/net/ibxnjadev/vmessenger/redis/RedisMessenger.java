package net.ibxnjadev.vmessenger.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ibxnjadev.vmessenger.universal.DefaultInterceptorHandler;
import net.ibxnjadev.vmessenger.universal.InterceptorHandler;
import net.ibxnjadev.vmessenger.universal.Messenger;
import net.ibxnjadev.vmessenger.universal.message.Message;
import net.ibxnjadev.vmessenger.universal.serialize.ObjectSerialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class RedisMessenger implements Messenger {

    private static final Logger logger = LoggerFactory.getLogger("RedisMessenger");
    private final InterceptorHandler interceptorHandler;
    private final ObjectSerialize objectSerialize;
    private final JedisPool jedisPool;

    private final ObjectMapper mapper;

    private final String channelName;

    public RedisMessenger(String channelName,
                          JedisPool jedisPool,
                          Jedis jedis,
                          ObjectSerialize objectSerialize,
                          ObjectMapper mapper) {
        this(channelName, new DefaultInterceptorHandler(objectSerialize), jedisPool, jedis, objectSerialize, mapper);
    }

    public RedisMessenger(String channelName,
                          InterceptorHandler interceptorHandler,
                          JedisPool jedisPool,
                          Jedis jedis,
                          ObjectSerialize objectSerialize,
                          ObjectMapper mapper) {
        this.interceptorHandler = interceptorHandler;
        this.objectSerialize = objectSerialize;
        this.jedisPool = jedisPool;
        this.channelName = channelName;
        this.mapper = mapper;

        RedisMessageListener redisMessageListener = new RedisMessageListener();
        CompletableFuture.runAsync(redisMessageListener);

        //RedisSubscriber subscriber = new RedisSubscriber(channelName, jedis, mapper, this);
    }

    @Override
    public <T> void sendMessage(T object) {
        Message message = new Message(object.getClass().getSimpleName()
                , objectSerialize.serialize(object));

        try (Jedis jedis = jedisPool.getResource()) {
            try {
                String messageString = mapper.writeValueAsString(message);
                jedis.publish(channelName, messageString);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    @Override
    public String getChannel() {
        return channelName;
    }

    @Override
    public InterceptorHandler getInterceptorHandler() {
        return interceptorHandler;
    }

    private class RedisMessageListener extends JedisPubSub implements Runnable {

        @Override
        public void run() {
            boolean first = true;
            while ((!Thread.interrupted() && !RedisMessenger.this.jedisPool.isClosed()) || !isSubscribed()) {
                if (!isSubscribed() && !first) {
                    RedisMessenger.logger.warn("Seems like redis pubsub has been unsubscribed, trying to re-subscribe to channel");
                }
                try (Jedis jedis = RedisMessenger.this.jedisPool.getResource()) {
                    if (first) {
                        first = false;
                    } else {
                        RedisMessenger.logger.info("Redis pubsub connection re-established");
                    }

                    jedis.subscribe(this, channelName);
                } catch (Exception e) {
                    RedisMessenger.logger.warn("Redis pubsub connection dropped, trying to re-open the connection", e);

                    try {
                        unsubscribe();
                    } catch (Exception ignored) {
                    }

                    // Sleep for 5 seconds to prevent massive spam in console
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        @Override
        public void onMessage(String channel, String content) {
            try {
                Message message = mapper.readValue(content, Message.class);
                RedisMessenger.this.call(message.getSubChannel(), message.getContent());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
