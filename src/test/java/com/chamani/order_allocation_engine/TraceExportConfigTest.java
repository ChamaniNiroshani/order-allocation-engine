package com.chamani.order_allocation_engine;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.context.TestPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** management.otlp.tracing.export.enabled is what decides whether spans leave the app; the prod profile turns it off. */
class TraceExportConfigTest {

    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(properties = "management.otlp.tracing.export.enabled=true")
    class ExportOn {
        @Autowired
        ApplicationContext context;

        @Test
        void exporterExists() {
            assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).isNotEmpty();
        }
    }

    /** Read from the file itself: the test config turns export off everywhere, which would hide a missing prod setting. */
    @Test
    void prodProfileTurnsExportOff() throws Exception {
        Properties prod = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
        assertThat(prod.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("false");
    }

    @Test
    void exportIsOnByDefaultForLocalDevelopment() throws Exception {
        Properties main = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        assertThat(main.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("true");
    }
}
