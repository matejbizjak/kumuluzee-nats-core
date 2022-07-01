package com.kumuluz.ee.nats.jetstream.consumer.listener;

import com.kumuluz.ee.nats.common.annotations.ConsumerConfig;
import com.kumuluz.ee.nats.common.connection.NatsConnection;
import com.kumuluz.ee.nats.common.connection.config.NatsConfigLoader;
import com.kumuluz.ee.nats.common.connection.config.NatsGeneralConfig;
import com.kumuluz.ee.nats.common.exception.NatsListenerException;
import com.kumuluz.ee.nats.common.util.AnnotatedInstance;
import com.kumuluz.ee.nats.common.util.SerDes;
import com.kumuluz.ee.nats.jetstream.JetStreamExtension;
import com.kumuluz.ee.nats.jetstream.annotations.JetStreamListener;
import com.kumuluz.ee.nats.jetstream.context.JetStreamContextFactory;
import io.nats.client.*;
import io.nats.client.api.ConsumerConfiguration;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessBean;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * @author Matej Bizjak
 */

public class ListenerInitializerExtension implements Extension {

    private static final Logger LOG = Logger.getLogger(ListenerInitializerExtension.class.getName());
    List<AnnotatedInstance<JetStreamListener, ConsumerConfig>> instanceList = new ArrayList<>();

    public <T> void processStreamListeners(@Observes ProcessBean<T> processBean) {
        for (Method method : processBean.getBean().getBeanClass().getMethods()) {
            if (method.getAnnotation(JetStreamListener.class) != null) {
                JetStreamListener jetStreamListenerAnnotation = method.getAnnotation(JetStreamListener.class);
                ConsumerConfig consumerConfigAnnotaion = null;
                if (method.getAnnotation(ConsumerConfig.class) != null) {
                    consumerConfigAnnotaion = method.getAnnotation(ConsumerConfig.class);
                }
                instanceList.add(new AnnotatedInstance<>(processBean.getBean(), method, jetStreamListenerAnnotation, consumerConfigAnnotaion));
            }
        }
    }

    public void after(@Observes AfterDeploymentValidation adv, BeanManager beanManager) {
        if (!JetStreamExtension.isExtensionEnabled()) {
            return;
        }

        for (AnnotatedInstance<JetStreamListener, ConsumerConfig> inst : instanceList) {
            LOG.info("Found JetStream listener method " + inst.getMethod().getName() + " in class " +
                    inst.getMethod().getDeclaringClass());
        }

        for (AnnotatedInstance<JetStreamListener, ConsumerConfig> inst : instanceList) {
            Method method = inst.getMethod();

            if (method.getParameterCount() != 1) {
                throw new NatsListenerException(String.format("Listener method must have exactly 1 parameter! Cause: %s"
                        , method));
            }

            Object reference = beanManager.getReference(inst.getBean(), method.getDeclaringClass()
                    , beanManager.createCreationalContext(inst.getBean()));
            Object[] args = new Object[method.getParameterCount()];

            NatsGeneralConfig generalConfig = NatsConfigLoader.getInstance().getGeneralConfig();
            JetStreamListener jetStreamListenerAnnotation = inst.getAnnotation1();
            ConsumerConfig consumerConfigAnnotation = inst.getAnnotation2();
            Connection connection = NatsConnection.getConnection(jetStreamListenerAnnotation.connection());
            try {
                JetStream jetStream = JetStreamContextFactory.getInstance().getContext(jetStreamListenerAnnotation.connection(), jetStreamListenerAnnotation.context());
                Dispatcher dispatcher = connection.createDispatcher();

                MessageHandler handler = msg -> {
                    Object receivedMsg;
                    try {
                        receivedMsg = SerDes.deserialize(msg.getData(), method.getParameterTypes()[0]);
                        args[0] = receivedMsg;
                    } catch (IOException e) {
                        exponentialNak(msg);
                        throw new NatsListenerException(String.format("Cannot deserialize the message as class %s!"
                                , method.getParameterTypes()[0].getSimpleName()), e);
                    }
                    try {
                        method.invoke(reference, args);
                        if (jetStreamListenerAnnotation.doubleAck()) {
                            ackSyncWithRetries(msg, generalConfig);
                        } else {
                            msg.ack();
                        }
                    } catch (InvocationTargetException | IllegalAccessException e) {
                        exponentialNak(msg);
                        // TODO glej da se ne zapre dispatcher
                        throw new NatsListenerException(String.format("Method %s could not be invoked.", method.getName()), e);
                    }
                };

                ConsumerConfiguration consumerConfiguration;
                if (consumerConfigAnnotation == null) {
                    consumerConfiguration = generalConfig.combineConsumerConfigAndBuild(null, null);
                } else {
                    consumerConfiguration = generalConfig.combineConsumerConfigAndBuild(consumerConfigAnnotation.name()
                            , consumerConfigAnnotation.configOverrides());
                }

                PushSubscribeOptions pushSubscribeOptions = PushSubscribeOptions.builder()
                        .configuration(consumerConfiguration)
                        .ordered(jetStreamListenerAnnotation.ordered())
                        .bind(jetStreamListenerAnnotation.bind())
                        .stream(jetStreamListenerAnnotation.stream())
                        .durable(jetStreamListenerAnnotation.durable())
                        .build();

                jetStream.subscribe(jetStreamListenerAnnotation.subject(), jetStreamListenerAnnotation.queue(), dispatcher, handler, false, pushSubscribeOptions);

            } catch (JetStreamApiException | IOException e) {
                LOG.severe(String.format("Cannot create a JetStream context for connection: %s", jetStreamListenerAnnotation.connection()));
            }
        }
    }

    private void ackSyncWithRetries(Message msg, NatsGeneralConfig generalConfig) {
        for (int retries = 0; ; retries++) {
            try {
                msg.ackSync(generalConfig.getAckConfirmationTimeout());
                return;
            } catch (InterruptedException | TimeoutException e) {
                if (retries < generalConfig.getAckConfirmationRetries()) {
                    try {
                        Thread.sleep(generalConfig.getAckConfirmationTimeout().toMillis());
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                } else {
                    throw new RuntimeException(e);  // TODO exceiptions -> log v celem fajlu?
                }
            }
        }
    }

    private void exponentialNak(Message msg) {
        msg.nakWithDelay(Duration.ofSeconds((long) Math.pow(5, msg.metaData().deliveredCount())));
    }
}
