package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.ServicioAsignado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ServicioAsignadoRepository extends JpaRepository<ServicioAsignado, Long> {
    List<ServicioAsignado> findByAtencionId(Long atencionId);
    /**
     * Busca servicios asignados por IDs de recursos
     */
    List<ServicioAsignado> findByVueloRecursoIdIn(List<Long> vueloRecursoIds);

    /**
     * NUEVO — Voucher grupal: busca los servicios asignados a CUALQUIER
     * atención que pertenezca al mismo grupoId. En los modos "un correo" y
     * "correo individual" (servicios compartidos) los servicios solo se
     * asignan UNA vez, a la atención "titular" del grupo.
     */
    @Query("SELECT sa FROM ServicioAsignado sa WHERE sa.atencion.grupoId = :grupoId")
    List<ServicioAsignado> findByAtencionGrupoId(@Param("grupoId") String grupoId);
}
