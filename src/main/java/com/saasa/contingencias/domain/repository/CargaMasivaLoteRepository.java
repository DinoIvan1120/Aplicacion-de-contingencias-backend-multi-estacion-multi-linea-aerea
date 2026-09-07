package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.CargaMasivaLote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CargaMasivaLoteRepository extends JpaRepository<CargaMasivaLote, Long> {

    /**
     * Búsqueda por el UUID público del lote (el que usa el frontend para
     * polling). Trae `cargadoPor` con JOIN FETCH a propósito: este lote se
     * consulta desde VoucherLoteOrchestratorImpl dentro de un hilo @Async,
     * FUERA de cualquier transacción/sesión de Hibernate — si cargadoPor
     * se queda LAZY (default), lote.getCargadoPor().getRol() revienta con
     * LazyInitializationException ("no Session") apenas se intenta leer,
     * matando el hilo async ANTES de procesar un solo pasajero y dejando
     * el lote huérfano en PROCESANDO para siempre.
     */
    @Query("SELECT l FROM CargaMasivaLote l JOIN FETCH l.cargadoPor WHERE l.loteId = :loteId")
    Optional<CargaMasivaLote> findByLoteId(@Param("loteId") String loteId);

    Optional<CargaMasivaLote> findByIdempotencyKey(String idempotencyKey);
}
