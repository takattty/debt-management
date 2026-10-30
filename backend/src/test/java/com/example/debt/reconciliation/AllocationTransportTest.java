package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AllocationTransportTest extends LedgerPublicFixtures {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void nullRowsInBothCommandsReturn400BeforeMappingAndLeaveEveryFactAndVersionUnchanged() throws Exception {
        String before = trace();
        for (String property : List.of("lines", "invoices")) {
            String path = "/api/payments/" + payment + "/allocations" + (property.equals("lines") ? "" : "/" + UUID.randomUUID() + "/reversal");
            Map<String, Object> entry = property.equals("lines") ? line("0") : Map.of("invoiceId", invoice, "expectedInvoiceVersion", "0");
            for (var rows : List.of(Arrays.asList((Object) null), Arrays.asList(entry, null), Arrays.asList(null, entry))) {
                String body = json.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "expectedPaymentVersion", "0", "reason", "Fictional null row", property, rows));
                mvc.perform(post(path).with(auth(user)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
                assertThat(trace()).isEqualTo(before);
            }
        }
    }

    @Test
    void exactDecimalVersionsAcrossJavascriptBoundaryReplayWithoutRoundingAndRejectInvalidOrExhaustedVersions() throws Exception {
        String path = "/api/payments/" + payment + "/allocations";
        long boundary = 9007199254740991L;
        jdbc.sql("UPDATE invoice SET version=? WHERE tenant_id=? AND id=?").params(boundary, tenant, invoice).update();
        for (int offset = 0; offset < 3; offset++) {
            String expected = Long.toString(boundary + offset);
            String body = request(Integer.toString(offset), expected);
            mvc.perform(post(path).with(auth(user)).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.paymentVersion").isString())
                    .andExpect(jsonPath("$.paymentVersion").value(Integer.toString(offset + 1)));
            mvc.perform(post(path).with(auth(user)).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.paymentVersion").value(Integer.toString(offset + 1)));
            assertThat(version()).isEqualTo(boundary + offset + 1);
        }
        mvc.perform(get("/api/payments/" + payment + "/candidates?limit=100").with(auth(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedPaymentVersion").value("3"));
        String before = trace();
        for (String stale : List.of("9007199254740991", "9007199254740992", "9007199254740993", "9223372036854775807")) {
            mvc.perform(post(path).with(auth(user)).with(csrf()).contentType("application/json").content(request(stale, Long.toString(version()))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
            assertThat(trace()).isEqualTo(before);
        }
        for (String invalid : List.of("01", "-1", "1.0", "1e0", "9223372036854775808", "99999999999999999999")) {
            mvc.perform(post(path).with(auth(user)).with(csrf()).contentType("application/json").content(request(invalid, Long.toString(version()))))
                    .andExpect(status().isBadRequest());
            assertThat(trace()).isEqualTo(before);
        }
        jdbc.sql("UPDATE invoice SET version=? WHERE tenant_id=? AND id=?").params(Long.MAX_VALUE, tenant, invoice).update();
        before = trace();
        mvc.perform(post(path).with(auth(user)).with(csrf()).contentType("application/json").content(request("3", Long.toString(Long.MAX_VALUE))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_EXHAUSTED"));
        assertThat(trace()).isEqualTo(before);
    }

    private String request(String paymentVersion, String invoiceVersion) {
        return json.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "expectedPaymentVersion", paymentVersion,
                "reason", "Fictional exact decimal version", "lines", List.of(line(invoiceVersion))));
    }
    private Map<String, Object> line(String version) {
        return Map.of("invoiceId", invoice, "expectedInvoiceVersion", version, "cashAmount", 1, "feeAmount", 0);
    }
}
