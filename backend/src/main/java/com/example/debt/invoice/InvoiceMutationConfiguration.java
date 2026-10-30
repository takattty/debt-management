package com.example.debt.invoice;

import com.example.debt.reconciliation.LedgerInvoiceMutationEligibility;
import com.example.debt.reconciliation.LedgerRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InvoiceMutationConfiguration {
    @Bean
    @ConditionalOnMissingBean(InvoiceMutationEligibility.class)
    InvoiceMutationEligibility ledgerEligibility(LedgerRepository ledger) { return new LedgerInvoiceMutationEligibility(ledger); }

    @Bean
    SmartInitializingSingleton requireSettlementAdapter(ObjectProvider<InvoiceMutationEligibility> eligibility) {
        return () -> {
            if (eligibility.getIfAvailable() == null) {
                throw new IllegalStateException("Reconciliation requires its real invoice mutation eligibility adapter");
            }
        };
    }
}
