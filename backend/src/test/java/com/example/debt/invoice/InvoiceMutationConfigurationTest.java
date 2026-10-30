package com.example.debt.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.debt.reconciliation.LedgerInvoiceMutationEligibility;
import com.example.debt.reconciliation.LedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InvoiceMutationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(InvoiceMutationConfiguration.class);

    @Test
    void missingRealLedgerFailsClosedEvenBeforePublicCommandsAreEnabled() {
        context.run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("debt.reconciliation.enabled=true").run(application -> assertThat(application).hasFailed());
    }

    @Test
    void realLedgerAdapterIsInstalledIndependentlyOfPublicCommandFlag() {
        for (boolean enabled : new boolean[] {false, true}) {
            context.withBean(LedgerRepository.class, () -> mock(LedgerRepository.class))
                    .withPropertyValues("debt.reconciliation.enabled=" + enabled)
                    .run(application -> assertThat(application.getBean(InvoiceMutationEligibility.class))
                            .isInstanceOf(LedgerInvoiceMutationEligibility.class));
        }
    }
}
