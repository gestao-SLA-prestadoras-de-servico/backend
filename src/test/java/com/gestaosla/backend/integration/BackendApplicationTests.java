package com.gestaosla.backend.integration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.TestcontainersConfiguration;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc 
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

    @Test
    @WithMockUser
    void shouldReturnHelloWorldMessage() throws Exception {
        mockMvc.perform(get("/hello-world"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "Hello World - Ambiente: test"
                ));
    }

    @Test
    void shouldConnectToDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
    }
}