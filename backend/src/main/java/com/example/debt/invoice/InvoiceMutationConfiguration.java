package com.example.debt.invoice;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class InvoiceMutationConfiguration {
    /** Issue #3 has no Settlement ledger or reconciliation dispatch; this adapter cannot accompany that feature. */
    static final class BeforeSettlement implements InvoiceMutationEligibility {
        @Override public void requireUnallocated(UUID tenant, UUID invoice) { }
    }

    @Bean
    @ConditionalOnMissingBean(InvoiceMutationEligibility.class)
    @ConditionalOnProperty(name = "debt.reconciliation.enabled", havingValue = "false", matchIfMissing = true)
    InvoiceMutationEligibility beforeSettlement() { return new BeforeSettlement(); }

    @Bean
    SmartInitializingSingleton requireSettlementAdapter(ObjectProvider<InvoiceMutationEligibility> eligibility, Environment environment) {
        return () -> {
            var adapter = eligibility.getIfAvailable();
            if (adapter == null || environment.getProperty("debt.reconciliation.enabled", Boolean.class, false)
                    && adapter instanceof BeforeSettlement) {
                throw new IllegalStateException("Reconciliation requires its real invoice mutation eligibility adapter");
            }
        };
    }
}
