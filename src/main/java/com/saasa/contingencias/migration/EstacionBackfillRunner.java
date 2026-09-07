package com.saasa.contingencias.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class EstacionBackfillRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EstacionBackfillRunner.class);

    private final EstacionBackfillService estacionBackfillService;
    private final boolean habilitado;

    public EstacionBackfillRunner(EstacionBackfillService estacionBackfillService,
                                  @Value("${app.migracion.backfill-estaciones.enabled:false}") boolean habilitado) {
        this.estacionBackfillService = estacionBackfillService;
        this.habilitado = habilitado;
    }

    @Override
    public void run(String... args) {
        if (!habilitado) {
            log.debug("Backfill Fase 1 (multi-estación) deshabilitado " +
                    "(app.migracion.backfill-estaciones.enabled=false). No se ejecuta.");
            return;
        }
        log.info("Ejecutando backfill Fase 1 (multi-estación)...");
        EstacionBackfillResultado resultado = estacionBackfillService.ejecutar();
        log.info("Resultado backfill Fase 1: {}", resultado);
    }
}
