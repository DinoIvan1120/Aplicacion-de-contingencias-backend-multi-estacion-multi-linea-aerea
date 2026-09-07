package com.saasa.contingencias.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class EnvValidator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EnvValidator.class);

    private static final List<String> REQUIRED_VARS = List.of(
        "DB_URL", "DB_USERNAME", "DB_PASSWORD",
        "JWT_SECRET", "JWT_EXPIRATION",
        "SPRING_PROFILES_ACTIVE", "CORS_ALLOWED_ORIGIN",
        "MAIL_HOST", "MAIL_PORT", "MAIL_USERNAME",
        "VERIFICATION_CODE_EXPIRATION_MINUTES",
        "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
        "AWS_REGION", "AWS_S3_BUCKET"
    );

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = REQUIRED_VARS.stream()
            .filter(v -> System.getenv(v) == null && System.getProperty(v) == null)
            .toList();
        if (!missing.isEmpty()) {
            log.error("Variables de entorno faltantes: {}", missing);
            throw new IllegalStateException("Faltan variables de entorno requeridas: " + missing);
        }
        log.info("EnvValidator: todas las variables requeridas están presentes.");
    }
}
