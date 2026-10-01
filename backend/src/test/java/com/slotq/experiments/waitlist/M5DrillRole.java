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
        @Bean org.springframework.boot.ApplicationRunner drillDatabaseProbe(EventDeliveryWorker worker) {
            return args->{
                String prefix=System.getenv("SLOTQ_DRILL_DB_PROBE");
                if(prefix==null)return;
                Thread.ofPlatform().daemon().name("drill-db-outage-probe").start(()->{
                    try {
                        while(!Files.exists(Path.of(prefix+".request")))Thread.sleep(100);
                        // The production scheduler deliberately redacts exceptions. Invoke the
                        // real scoped production cycle once, while the parent has paused MySQL.
                        try {worker.runCycle();throw new IllegalStateException("DB outage probe did not fail");}
                        catch(RuntimeException failure) {
                            var observed=new LinkedHashMap<>(databaseFailure(failure));
                            observed.put("at",java.time.Instant.now().toString());
                            observed.put("pid",ProcessHandle.current().pid());
                            observed.put("invocationSource","test-only explicit production cycle during paused MySQL");
                            Files.writeString(Path.of(prefix+".json"),M5TransportComparisonRunner.JSON.writeValueAsString(observed));
                        }
                    } catch(Exception failure){throw new IllegalStateException("DB outage probe evidence unavailable",failure);}
                });
            };
        }
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

    static Map<String,Object> databaseFailure(RuntimeException failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var sqlClasses=new TreeSet<String>();boolean productionCycle=false;
        for(Throwable cause=failure;cause!=null&&seen.add(cause)&&seen.size()<=32;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException)sqlClasses.add(cause.getClass().getName());
            productionCycle|=Arrays.stream(cause.getStackTrace()).anyMatch(frame->
                frame.getClassName().equals(EventDeliveryWorker.class.getName())&&frame.getMethodName().equals("runCycle"));
        }
        M5TransportComparisonRunner.require(productionCycle&&!sqlClasses.isEmpty(),"no actual production-cycle JDBC failure");
        // Never persist exception messages, SQL, stack trace text or connection credentials.
        return Map.of("entrypoint","EventDeliveryWorker.runCycle","exceptionClasses",List.copyOf(sqlClasses));
    }
}
