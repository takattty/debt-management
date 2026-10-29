package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.example.debt.access.AuthFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class CustomerImportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean(name = "importClock") Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private MockHttpSession alpha;

    @BeforeEach
    void loginWithDedicatedImportClock() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        alpha = AuthFixtures.login(mvc, "alpha-accountant@example.test");
    }

    @AfterEach
    void removeOnlyTheseImportFixturesBeforeOtherSuitesDeleteTheirUsers() {
        for (String table : List.of("import_error", "import_row", "import_batch")) {
            jdbc.sql("DELETE FROM " + table + " WHERE tenant_id IN (?,?)").params(AuthFixtures.ALPHA, AuthFixtures.BETA).update();
        }
    }

    @Test
    void storesImmutablePreviewWithoutAnyCustomerOrCustomerAuditAndSeparatesEveryReadByTenant() throws Exception {
        long customers = count("customer");
        UUID id = preview(CustomerCsv.HEADER + "P-" + UUID.randomUUID() + ",架空商事😀\n");
        mvc.perform(get("/api/imports/" + id).session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREVIEW_VALID")).andExpect(jsonPath("$.confirmedCount").value(0))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-30T12:00:00Z"));
        mvc.perform(get("/api/imports/" + id + "/rows").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].customerName").value("架空商事😀")).andExpect(jsonPath("$.nextCursor").value(0));
        var beta = AuthFixtures.login(mvc, "beta-admin@example.test");
        for (String suffix : List.of("", "/rows", "/errors", "/errors.csv")) {
            mvc.perform(get("/api/imports/" + id + suffix).session(beta).header("X-Tenant-Id", AuthFixtures.ALPHA))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(count("customer")).isEqualTo(customers);
        when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
        mvc.perform(get("/api/imports/" + id).session(alpha)).andExpect(status().isOk());
    }

    @Test
    void returnsEveryErrorThroughStablePagesAndSafeCsvFromSameSnapshot() throws Exception {
        UUID id = preview(CustomerCsv.HEADER + "=SUM(A1),\n\"=SUM(A1)\",\n");
        mvc.perform(get("/api/imports/" + id).session(alpha)).andExpect(jsonPath("$.errorCount").value(2));
        mvc.perform(get("/api/imports/" + id + "/errors?limit=1").session(alpha))
                .andExpect(jsonPath("$.items[0].rowNumber").value(2)).andExpect(jsonPath("$.items[0].field").value("customer_name"))
                .andExpect(jsonPath("$.nextCursor").value(1));
        mvc.perform(get("/api/imports/" + id + "/errors?cursor=1&limit=1").session(alpha))
                .andExpect(jsonPath("$.items[0].rowNumber").value(3)).andExpect(jsonPath("$.nextCursor").value(0));
        var csv = mvc.perform(get("/api/imports/" + id + "/errors.csv").session(alpha)).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsByteArray()).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        assertThat(csv.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"import-errors.csv\"");
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"2\",\"customer_name\"", "\"3\",\"customer_name\"");
        UUID dangerous = preview(CustomerCsv.HEADER + "\"=SUM(A1)" + "x".repeat(51) + "\",A\n");
        var safe = mvc.perform(get("/api/imports/" + dangerous + "/errors.csv").session(alpha)).andReturn();
        assertThat(safe.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"'=SUM(A1)");
    }

    @Test
    void rejectsMissingCsrfExtraPartsAndInvalidPageBoundsAndAnonymousAccess() throws Exception {
        mvc.perform(get("/api/imports/customers/template")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/imports/customers/preview").file(file(CustomerCsv.HEADER)).session(alpha))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/imports/customers/preview").file(file(CustomerCsv.HEADER)).session(alpha).with(csrf().asHeader())
                        .param("tenantId", AuthFixtures.BETA.toString())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/imports/customers/preview").file(file(CustomerCsv.HEADER)).file(file(CustomerCsv.HEADER))
                        .session(alpha).with(csrf().asHeader())).andExpect(status().isBadRequest());
        UUID id = preview(CustomerCsv.HEADER + "P-" + UUID.randomUUID() + ",A\n");
        for (String query : List.of("limit=0", "limit=101", "cursor=-1", "cursor=2147483647")) {
            mvc.perform(get("/api/imports/" + id + "/rows?" + query).session(alpha)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void acceptsCorrectedNewPreviewAndKeepsOldErrorsIncludingNulVisible() throws Exception {
        UUID invalid = preview(CustomerCsv.HEADER + "N-" + UUID.randomUUID() + ",bad\0name\n");
        mvc.perform(get("/api/imports/" + invalid + "/errors").session(alpha))
                .andExpect(jsonPath("$.items[0].inputValue").value("bad␀name"));
        UUID repaired = preview(CustomerCsv.HEADER + "N-" + UUID.randomUUID() + ",架空商事\n");
        assertThat(repaired).isNotEqualTo(invalid);
        mvc.perform(get("/api/imports/" + repaired + "/errors").session(alpha)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/imports/" + invalid).session(alpha)).andExpect(jsonPath("$.status").value("PREVIEW_INVALID"));
        preview("customer_code,customer_n\0ame\n");
        preview(CustomerCsv.HEADER + "C1,bad\0\"quote\n");
    }

    @Test
    void templateHasExactFixedHeaderAndBomAndNoSampleBusinessRows() throws Exception {
        var response = mvc.perform(get("/api/imports/customers/template").session(alpha)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("\ufeff" + CustomerCsv.HEADER);
    }

    @Test
    void existingCustomerLookupRejectsOnlyCodesInTheAuthenticatedTenant() throws Exception {
        UUID alphaCustomer = UUID.randomUUID(); UUID betaCustomer = UUID.randomUUID();
        String alphaCode = "A-" + UUID.randomUUID(); String betaCode = "B-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'Fictional existing alpha'),(?,?,?,'Fictional existing beta')")
                .params(alphaCustomer, AuthFixtures.ALPHA, alphaCode, betaCustomer, AuthFixtures.BETA, betaCode).update();
        try {
            UUID id = preview(CustomerCsv.HEADER + alphaCode + ",A\n" + betaCode + ",B\n");
            mvc.perform(get("/api/imports/" + id + "/errors").session(alpha))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].reasonCode").value("CUSTOMER_EXISTS"))
                    .andExpect(jsonPath("$.items[0].inputValue").value(alphaCode));
            mvc.perform(get("/api/imports/" + id + "/rows").session(alpha))
                    .andExpect(jsonPath("$.items[0].valid").value(false)).andExpect(jsonPath("$.items[1].valid").value(true));
        } finally {
            jdbc.sql("DELETE FROM customer WHERE id IN (?,?)").params(alphaCustomer, betaCustomer).update();
        }
    }

    private UUID preview(String csv) throws Exception {
        var response = mvc.perform(multipart("/api/imports/customers/preview").file(file(csv)).session(alpha).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asString());
    }

    private MockMultipartFile file(String value) {
        return new MockMultipartFile("file", "fictional.csv", "text/csv", value.getBytes(StandardCharsets.UTF_8));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
