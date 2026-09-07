package com.saasa.contingencias.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Executor dedicado para el procesamiento en background de lotes de
 * carga masiva (generar PDF + subir a S3 + enviar email/WhatsApp por
 * cada pasajero o grupo).
 *
 * Se define un pool ACOTADO en vez de dejar que @Async use el executor
 * por defecto de Spring (SimpleAsyncTaskExecutor), que crea un hilo
 * nuevo SIN LÍMITE por cada tarea. Con lotes de ~100 pasajeros y varios
 * agentes cargando al mismo tiempo, eso podría agotar recursos del
 * servidor. Este pool limita la concurrencia real de envíos a 6 hilos,
 * con una cola de espera de 100 tareas antes de rechazar.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "cargaMasivaExecutor")
    public Executor cargaMasivaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("carga-masiva-");
        executor.initialize();
        return executor;
    }
}
