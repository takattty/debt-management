package com.example.debt.customer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CustomerService {
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Customer> list(UUID tenantId) {
        return jdbc.sql("SELECT id, code, name, version FROM customer WHERE tenant_id = :tenant ORDER BY code")
                .param("tenant", tenantId).query(Customer.class).list();
    }

    @Transactional
    public Customer create(UUID tenantId, UUID actor, CustomerValues values) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer (id, tenant_id, code, name) VALUES (:id, :tenant, :code, :name)")
                .param("id", id).param("tenant", tenantId).param("code", values.code())
                .param("name", values.name()).update();
        audit(tenantId, actor, "CUSTOMER_CREATED", id, values);
        return new Customer(id, values.code(), values.name(), 0);
    }

    @Transactional
    public Customer update(UUID tenantId, UUID actor, UUID id, CustomerValues values, long version) {
        int count = jdbc.sql("""
                UPDATE customer SET code = :code, name = :name, version = version + 1,
                  updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND tenant_id = :tenant AND version = :version
                """)
                .param("id", id).param("tenant", tenantId).param("code", values.code())
                .param("name", values.name()).param("version", version).update();
        if (count == 0) {
            boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM customer WHERE id = :id AND tenant_id = :tenant)")
                    .param("id", id).param("tenant", tenantId).query(Boolean.class).single();
            if (!exists) {
                throw new CustomerNotFound();
            }
            throw new CustomerConflict();
        }
        audit(tenantId, actor, "CUSTOMER_UPDATED", id, values);
        return new Customer(id, values.code(), values.name(), version + 1);
    }

    private void audit(UUID tenantId, UUID actor, String action, UUID id, CustomerValues values) {
        String details = json.writeValueAsString(Map.of("code", values.code(), "name", values.name()));
        jdbc.sql("""
                INSERT INTO audit_event (id, tenant_id, actor, actor_user_id, action, entity_id, details)
                VALUES (:id, :tenant, :actor, :user, :action, :entity, CAST(:details AS jsonb))
                """)
                .param("id", UUID.randomUUID()).param("tenant", tenantId).param("actor", "user:" + actor).param("user", actor)
                .param("action", action).param("entity", id).param("details", details).update();
    }
}
