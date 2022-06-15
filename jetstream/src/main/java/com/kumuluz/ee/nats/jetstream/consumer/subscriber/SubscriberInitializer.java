package com.kumuluz.ee.nats.jetstream.consumer.subscriber;

import com.kumuluz.ee.nats.common.annotations.ConsumerConfig;
import com.kumuluz.ee.nats.jetstream.annotations.JetStreamSubscriber;
import io.nats.client.JetStreamSubscription;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.InjectionPoint;

/**
 * @author Matej Bizjak
 */

@Dependent
public class SubscriberInitializer {

    @Produces
    @JetStreamSubscriber
    public static JetStreamSubscription getSubscription(InjectionPoint injectionPoint) {
        JetStreamSubscriber jetStreamSubscriberAnnotation = injectionPoint.getAnnotated().getAnnotation(JetStreamSubscriber.class);
        return SubscriberFactory.getInstance().getSubscription(jetStreamSubscriberAnnotation, null);
    }

    @Produces
    @JetStreamSubscriber
    @ConsumerConfig
    public static JetStreamSubscription getSubscription2(InjectionPoint injectionPoint) {
        JetStreamSubscriber jetStreamSubscriberAnnotation = injectionPoint.getAnnotated().getAnnotation(JetStreamSubscriber.class);
        ConsumerConfig consumerConfigAnnotation = injectionPoint.getAnnotated().getAnnotation(ConsumerConfig.class);
        return SubscriberFactory.getInstance().getSubscription(jetStreamSubscriberAnnotation, consumerConfigAnnotation);
    }
}
