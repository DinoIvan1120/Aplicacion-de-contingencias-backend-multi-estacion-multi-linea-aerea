package com.saasa.contingencias.domain.enumeration;
public enum RolEnum { ADMINISTRADOR, LIDER_SAASA, AGENTE_SAASA, LINEA_AEREA, PROVEEDOR;
    /**
     * Roles operativos SAASA cuyo correo es OPCIONAL y que pueden iniciar
     * sesión con su DNI (documento) + contraseña.
     *
     * Es la única fuente de verdad de esta regla: login, registro y
     * actualización de usuarios la consultan aquí, así que para habilitar
     * otro rol en el futuro basta con agregarlo en este método.
     */
    public boolean permiteLoginPorDni() {
        return this == AGENTE_SAASA || this == LIDER_SAASA;
    }
}
