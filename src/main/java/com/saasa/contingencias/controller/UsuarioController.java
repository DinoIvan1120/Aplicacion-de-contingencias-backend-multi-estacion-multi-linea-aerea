package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest;
import com.saasa.contingencias.domain.dto.request.UsuarioRequest;
import com.saasa.contingencias.domain.dto.response.UsuarioEstacionResponse;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.service.IUsuarioService;
import com.saasa.contingencias.util.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/usuarios")
@Tag(name = "Usuarios")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class UsuarioController {

    private final IUsuarioService usuarioService;

    public UsuarioController(IUsuarioService usuarioService) { this.usuarioService = usuarioService; }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<UsuarioResponse>>> findAll(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(usuarioService.findAll(pageable)));
    }

    // ─── GET /usuarios/buscar — búsqueda dinámica multi-campo ─────────────────
    /**
     * Endpoint de búsqueda óptima con filtros opcionales combinados.
     *
     * Todos los parámetros son opcionales. Solo se incluyen en el WHERE de la
     * query los que se envíen con valor. Si no se envía ningún filtro,
     * devuelve todos los usuarios paginados (equivalente a GET /usuarios).
     *
     * Ejemplos de uso:
     *   GET /usuarios/buscar?nombre=roberto
     *   GET /usuarios/buscar?rol=LIDER_SAASA&estado=1
     *   GET /usuarios/buscar?correo=saasa.com&rol=AGENTE_SAASA
     *   GET /usuarios/buscar?nombre=garcia&estado=0&page=0&size=10
     *   GET /usuarios/buscar?codigoEmpleado=ADM&documento=12345
     *
     * El parámetro 'nombre' busca simultáneamente en los campos
     * 'nombre' Y 'apellido' del usuario (con OR interno).
     */
    @GetMapping("/buscar")
    @Operation(
            summary = "Búsqueda dinámica de usuarios por múltiples campos",
            description = """
            Busca usuarios combinando cualquier combinación de filtros con AND.
            Todos los parámetros son opcionales. Los no enviados se ignoran.

            - **nombre**: busca en campo 'nombre' OR 'apellido' (LIKE, sin mayúsculas)
            - **correo**: búsqueda parcial en correo (LIKE, sin mayúsculas)
            - **documento**: búsqueda parcial en documento (LIKE, sin mayúsculas)
            - **codigoEmpleado**: búsqueda parcial en código de empleado (LIKE)
            - **rol**: filtro exacto. Valores: ADMINISTRADOR | LIDER_SAASA | AGENTE_SAASA | LINEA_AEREA | PROVEEDOR
            - **estado**: filtro exacto. Valores: 1 (activo) | 0 (inactivo)
            - **page**, **size**, **sort**: paginación estándar Spring Boot
            """
    )
    public ResponseEntity<ApiResponse<Page<UsuarioResponse>>> buscar(
            @Parameter(description = "Busca en nombre O apellido (parcial, sin distinción de mayúsculas)")
            @RequestParam(required = false) String nombre,

            @Parameter(description = "Busca en correo electrónico (parcial)")
            @RequestParam(required = false) String correo,

            @Parameter(description = "Busca en documento (parcial)")
            @RequestParam(required = false) String documento,

            @Parameter(description = "Busca en código de empleado (parcial)")
            @RequestParam(required = false) String codigoEmpleado,

            @Parameter(description = "Filtro exacto por rol: ADMINISTRADOR | LIDER_SAASA | AGENTE_SAASA | LINEA_AEREA | PROVEEDOR")
            @RequestParam(required = false) RolEnum rol,

            @Parameter(description = "Filtro exacto por estado: 1=activo, 0=inactivo")
            @RequestParam(required = false) Integer estado,

            @PageableDefault(size = 20, sort = "nombre", direction = Sort.Direction.ASC)
            Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(
                usuarioService.buscar(nombre, correo, documento,
                        codigoEmpleado, rol, estado, pageable)));
    }


    @PostMapping
    public ResponseEntity<ApiResponse<UsuarioResponse>> create(@Valid @RequestBody UsuarioRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(usuarioService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UsuarioResponse>> update(@PathVariable Long id, @Valid @RequestBody UsuarioRequest request) {
        return ResponseEntity.ok(ApiResponse.success(usuarioService.update(id, request)));
    }

    @PatchMapping("/{id}/estado")
    public ResponseEntity<ApiResponse<Void>> changeEstado(@PathVariable Long id, @RequestParam Integer estado) {
        usuarioService.changeEstado(id, estado);
        return ResponseEntity.ok(ApiResponse.success("Estado actualizado", null));
    }

    // ─── Estaciones asignadas al usuario (Fase 2 — multi-estación) ────────────
    @GetMapping("/{id}/estaciones")
    public ResponseEntity<ApiResponse<List<UsuarioEstacionResponse>>> findEstaciones(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(usuarioService.findEstaciones(id)));
    }

    @PostMapping("/{id}/estaciones")
    public ResponseEntity<ApiResponse<UsuarioEstacionResponse>> asignarEstacion(
            @PathVariable Long id, @Valid @RequestBody AsignarEstacionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(usuarioService.asignarEstacion(id, request)));
    }

    @DeleteMapping("/{id}/estaciones/{relacionId}")
    public ResponseEntity<ApiResponse<Void>> quitarEstacion(@PathVariable Long id, @PathVariable Long relacionId) {
        usuarioService.quitarEstacion(id, relacionId);
        return ResponseEntity.ok(ApiResponse.success("Estación desasignada", null));
    }
}
