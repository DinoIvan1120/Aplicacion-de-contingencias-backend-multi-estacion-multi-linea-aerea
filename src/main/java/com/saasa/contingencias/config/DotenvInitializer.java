package com.saasa.contingencias.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Carga el archivo .env correspondiente al perfil activo ANTES de que
 * Spring resuelva cualquier @Value o application-{perfil}.properties.
 *
 * Orden de resolución del perfil:
 *   1. Variable de entorno del SO:  SPRING_PROFILES_ACTIVE
 *   2. System property de JVM:      -Dspring.profiles.active=dev
 *   3. Fallback por defecto:        dev
 *
 * Archivos que carga según perfil:
 *   dev  →  .env.dev
 *   qa   →  .env.qa
 *   prd  →  .env.prd
 *
 * Se registra en application.properties via:
 *   context.initializer.classes=com.{tugrupo}.{tuapp}.config.DotenvInitializer
 */
public class DotenvInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {

        // 1. Detectar perfil activo
        String profile = resolveProfile();
        String envFile = ".env." + profile;

        System.out.println("[DotenvInitializer] Perfil activo: " + profile);
        System.out.println("[DotenvInitializer] Cargando: " + envFile);

        // 2. Cargar el archivo .env del perfil
        Dotenv dotenv = Dotenv.configure()
                .filename(envFile)
                .ignoreIfMissing()   // No falla si el archivo no existe (útil en CI/CD con vars de SO)
                .load();

        // 3. Inyectar variables en Spring Environment y como System properties
        Map<String, Object> envVars = new HashMap<>();

        for (DotenvEntry entry : dotenv.entries()) {
            String key   = entry.getKey();
            String value = entry.getValue();

            // Inyectar en Spring Environment (resuelve ${VAR} en application.properties)
            envVars.put(key, value);

            // System property como fallback (resuelve @Value en beans)
            if (System.getenv(key) == null) {
                System.setProperty(key, value);
            }
        }

        // 4. Registrar con alta prioridad para que sobreescriba cualquier otra fuente
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("dotenvProperties", envVars));

        // 5. Activar el perfil en Spring para que cargue application-{perfil}.properties
        ctx.getEnvironment().setActiveProfiles(profile);
    }

    /**
     * Resuelve el perfil en este orden:
     *  1. Variable de entorno del SO (SPRING_PROFILES_ACTIVE)
     *  2. System property de JVM (-Dspring.profiles.active)
     *  3. Fallback: "dev"
     */
    private String resolveProfile() {
        // Primero: variable de entorno del SO (Docker, CI/CD, servidor)
        String fromEnv = System.getenv("SPRING_PROFILES_ACTIVE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }

        // Segundo: system property pasada por JVM (-Dspring.profiles.active=qa)
        String fromJvm = System.getProperty("spring.profiles.active");
        if (fromJvm != null && !fromJvm.isBlank()) {
            return fromJvm.trim();
        }

        // Fallback: dev para desarrollo local
        return "dev";
    }
}
