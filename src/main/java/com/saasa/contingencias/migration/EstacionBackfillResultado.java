package com.saasa.contingencias.migration;

public record EstacionBackfillResultado(
        boolean ejecutado,
        String motivoOmision,
        Long estacionLimaId,
        int lineasAereasCreadas,
        int vuelosActualizados,
        int proveedoresActualizados,
        int registrosVueloDiarioActualizados,
        int atencionesActualizadas,
        int serviciosProveedorActualizados,
        int vueloRecursosActualizados,
        int serviciosAsignadosActualizados,
        int enviosPdfActualizados,
        int auditoriaActualizada,
        int usuariosAsignados
) {
    public static EstacionBackfillResultado omitido(String motivo) {
        return new EstacionBackfillResultado(false, motivo, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public int totalRegistrosOperativosActualizados() {
        return vuelosActualizados + proveedoresActualizados + registrosVueloDiarioActualizados
                + atencionesActualizadas + serviciosProveedorActualizados + vueloRecursosActualizados
                + serviciosAsignadosActualizados + enviosPdfActualizados;
    }
}