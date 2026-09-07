package com.saasa.contingencias.config.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    // Valores por defecto vacíos para evitar que falle el arranque
    // si el .env aún no fue cargado. El DotenvInitializer los reemplaza.
    @Value("${aws.access-key-id}")
    private String accessKeyId;

    @Value("${aws.secret-access-key}")
    private String secretAccessKey;

    @Value("${aws.region}")
    private String region;

    @Value("${aws.s3.bucket}")      // ← nuevo
    private String bucket;

    @Value("${aws.s3.prefix}")      // ← nuevo
    private String prefix;

    @Bean
    public S3Client s3Client() {
        if ("NOT_SET".equals(accessKeyId) || "NOT_SET".equals(secretAccessKey)) {
            log.warn("⚠️ AWS credentials no configuradas — S3Client en modo local/mock");
        } else {
            log.info("✅ S3Client configurado con región: {}", region);
        }

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();
    }

    /**
     * ✅ Bean de S3Presigner para generar URLs firmadas
     *
     * Las URLs firmadas permiten descargas temporales sin autenticación
     */
    @Bean
    public S3Presigner s3Presigner() {
        log.info("✅ S3Presigner configurado para URLs firmadas");

        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();
    }
}
