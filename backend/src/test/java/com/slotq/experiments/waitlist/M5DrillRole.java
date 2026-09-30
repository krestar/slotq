package com.slotq.experiments.waitlist;

import java.nio.file.*;
import java.util.*;
import com.slotq.SlotqApplication;
import com.slotq.events.application.*;
import com.slotq.events.persistence.JdbcKafkaPublicationLedger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Test-only process fault adapters; all normal intake/execution/publication entrypoints are production. */
public final class M5DrillRole {
    public static void main(String[]args) {new SpringApplicationBuilder(SlotqApplication.class,Faults.class).run(args);}
    @TestConfiguration(proxyBeanMethods=false)
    static class Faults {
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor drillHandlerFault() {
            String file=System.getenv("SLOTQ_DRILL_HANDLER_FAULT");
            return new org.springframework.beans.factory.config.BeanPostProcessor(){
                public Object postProcessAfterInitialization(Object bean,String name) {
                    if(file!=null && bean instanceof com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler && bean instanceof org.springframework.aop.framework.Advised proxy) {
                        proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)invocation->{
                            if(invocation.getMethod().getName().equals("handle")&&Files.exists(Path.of(file))) {
                                if(Files.readString(Path.of(file)).equals("hold")) {
                                    while(Files.exists(Path.of(file)))Thread.sleep(100);
                                } else throw new EventHandlingException(DeliveryFailure.TARGET_HANDLER_MISSING);
                            }
                            return invocation.proceed();
                        });
                    }
                    return bean;
                }
            };
        }
        @Bean @Primary KafkaPublicationLedger drillLedger(JdbcKafkaPublicationLedger delegate) {
            return new KafkaPublicationLedger(){
                public int discover(String destination,int batch){return delegate.discover(destination,batch);}
                public List<Key> candidates(int batch){return delegate.candidates(batch);}
                public Optional<Claim> claim(Key key,KafkaPublicationPolicy policy){return delegate.claim(key,policy);}
                public Publication load(Claim claim){return delegate.load(claim);}
                public void published(Claim claim,int partition,long offset){
                    String path=System.getenv("SLOTQ_DRILL_ACK_CRASH");
                    if(path!=null) {
                        try {Files.writeString(Path.of(path),M5TransportComparisonRunner.JSON.writeValueAsString(Map.of("eventId",claim.key().eventId(),"partition",partition,"offset",offset,"at",java.time.Instant.now().toString(),"pid",ProcessHandle.current().pid())));}catch(Exception failure){throw new IllegalStateException(failure);}
                        Runtime.getRuntime().halt(111);
                    }
                    delegate.published(claim,partition,offset);
                }
                public void failed(Claim claim,String code,boolean retryable,KafkaPublicationPolicy policy){delegate.failed(claim,code,retryable,policy);}
                public Inventory inventory(){return delegate.inventory();}
                public void verifyTopic(String destination,String id,Map<Integer,Long> starts){delegate.verifyTopic(destination,id,starts);}
            };
        }
    }
}
