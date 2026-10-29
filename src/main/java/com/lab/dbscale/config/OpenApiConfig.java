package com.lab.dbscale.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI labOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("DB Scalability Lab API")
                .version("1.0.0")
                .description("""
                    Hands-on Spring Boot + MySQL lab for database scalability patterns.

                    **Labs covered:**
                    | Lab | Layer | Problem → Fix |
                    |-----|-------|---------------|
                    | #1  | Query | Full scan → Composite index |
                    | #2  | Query | N+1 queries → JOIN FETCH |
                    | #3  | Query | OFFSET pagination → Keyset |
                    | #4  | App   | HikariCP pool exhaustion |
                    | #5  | App   | Single-row inserts → Batch writes |
                    | #6  | App   | DB reads → Redis @Cacheable |
                    | #7  | Infra | All reads on primary → Read replica |
                    | #8  | Infra | Optimistic lock conflicts → Atomic UPDATE |
                    | #9  | Data  | Slow DELETE → RANGE partitioning |
                    | #10 | Infra | HPA connection bomb → ProxySQL |
                    | #11 | Data  | Sync writes → Kafka async outbox |

                    **Quick start:**
                    ```
                    docker compose -f docker-compose-lab.yml up --build
                    ```
                    Then open **http://localhost:8080** for the interactive lab guide.
                    """)
                .contact(new Contact()
                    .name("DB Scalability Lab")
                    .url("http://localhost:8080")))
            .tags(List.of(
                new Tag().name("Orders")
                         .description("Order endpoints — Labs #1 #2 #3 #6 #11"),
                new Tag().name("Products")
                         .description("Product/stock endpoints — Lab #8"),
                new Tag().name("Lab Utilities")
                         .description("Seed status and sample data — used by the embedded guide")
            ));
    }
}
