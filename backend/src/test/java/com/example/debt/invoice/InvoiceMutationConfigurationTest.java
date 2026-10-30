package com.example.debt.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class InvoiceMutationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(InvoiceMutationConfiguration.class);

    @Test
    void preSettlementAdapterExistsOnlyBeforeReconciliationIsEnabled() {
        context.run(application -> assertThat(application.getBean(InvoiceMutationEligibility.class))
                .isInstanceOf(InvoiceMutationConfiguration.BeforeSettlement.class));
        context.withPropertyValues("debt.reconciliation.enabled=true").run(application -> assertThat(application).hasFailed());
    }

    @Test
    void enabledReconciliationRequiresAnExplicitReplacementAdapter() {
        context.withUserConfiguration(Replacement.class).withPropertyValues("debt.reconciliation.enabled=true")
                .run(application -> assertThat(application).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class Replacement {
        @Bean InvoiceMutationEligibility realAdapterFixture() {
            return (tenant, invoice) -> { throw new InvoiceConflict("INVOICE_ALLOCATED"); };
        }
    }
}
