package com.cl2.integration.integration.sap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
public class SapPullingScheduler {

    @Scheduled(cron = "${integration.sap.customer.pulling.cron:0 */10 * * * *}")
    @SchedulerLock(
        name = "SAP_Customer_Pulling_Task", 
        lockAtMostFor = "14m", 
        lockAtLeastFor = "1m"
    )
    public void executeCustomerPullingTask() {
        // 1. Carga perfiles activos con externalSource = 'sap' y businessDomain = 'customers'
        // 2. Ejecuta la estrategia correspondiente (HANA DB o OData)
        // 3. Procesa registros e incrementa watermark
    }
}
