package com.slotq.experiments.waitlist;

import java.sql.SQLTransientConnectionException;
import com.slotq.events.application.EventDeliveryWorker;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class M5DrillRoleTests {
    @Test void recordsOnlySqlClassAndProductionEntrypointWithoutSensitiveExceptionText() throws Exception {
        var sql=new SQLTransientConnectionException("jdbc password=secret; SQL contains tenant data");
        var failure=new IllegalStateException("sensitive outer message",sql);
        failure.setStackTrace(new StackTraceElement[]{cycle()});
        var observed=M5DrillRole.databaseFailure(failure);
        assertThat(observed).containsEntry("entrypoint","EventDeliveryWorker.runCycle");
        assertThat(M5TransportComparisonRunner.JSON.writeValueAsString(observed))
            .contains("java.sql.SQLTransientConnectionException").doesNotContain("secret","tenant data","sensitive");
    }
    @Test void sqlFailureFromStartupOrAnotherQueryCannotProveAnExecutorOutage() {
        var failure=new IllegalStateException(new SQLTransientConnectionException());
        assertThatThrownBy(()->M5DrillRole.databaseFailure(failure)).hasMessageContaining("production-cycle JDBC failure");
    }
    @Test void nonSqlExecutorFailureCannotProveAnActualDatabaseOutage() {
        var failure=new IllegalStateException("handler error");failure.setStackTrace(new StackTraceElement[]{cycle()});
        assertThatThrownBy(()->M5DrillRole.databaseFailure(failure)).hasMessageContaining("production-cycle JDBC failure");
    }
    private static StackTraceElement cycle(){return new StackTraceElement(EventDeliveryWorker.class.getName(),"runCycle","EventDeliveryWorker.java",50);}
}
