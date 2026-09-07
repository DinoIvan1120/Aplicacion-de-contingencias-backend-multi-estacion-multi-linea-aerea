package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.AuthResponse;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IAuthService;
import com.saasa.contingencias.service.IEmailService;
import com.saasa.contingencias.util.DateTimeUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuthServiceImpl implements IAuthService {

    private final UsuarioRepository usuarioRepository;
    private final CodigoVerificacionRepository codigoVerificacionRepository;
    private final UsuarioEstacionRepository usuarioEstacionRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final IEmailService emailService;

    @Value("${verification.code.expiration.minutes:10}")
    private int codeExpirationMinutes;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpiration;

    public AuthServiceImpl(UsuarioRepository usuarioRepository,
                           CodigoVerificacionRepository codigoVerificacionRepository,
                           UsuarioEstacionRepository usuarioEstacionRepository,
                           JwtUtil jwtUtil, PasswordEncoder passwordEncoder, IEmailService emailService) {
        this.usuarioRepository = usuarioRepository;
        this.codigoVerificacionRepository = codigoVerificacionRepository;
        this.usuarioEstacionRepository = usuarioEstacionRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    /**
     * Login unificado que soporta dos modos:
     *  - correo + password  → todos los roles (flujo original)
     *  - dni    + password  → solo AGENTE_SAASA (flujo nuevo)
     *
     * Si llegan ambos campos se prioriza el correo para no romper clientes
     * existentes que ya envíen ambos por error.
     */
    @Override
    public AuthResponse login(LoginRequest request) {
        Usuario user = resolverUsuarioPorIdentificador(request.correo(), request.dni());

        if (user.getEstado() == 0)
            throw new AccesoDenegadoException("Usuario inactivo");

        // Solo AGENTE_SAASA puede iniciar sesión por DNI
        if (esLoginPorDni(request) && user.getRol() != RolEnum.AGENTE_SAASA)
            throw new AccesoDenegadoException("El login por DNI está disponible únicamente para el rol AGENTE_SAASA");

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash()))
            throw new BadRequestException("Credenciales inválidas");

        // El subject del JWT siempre es el correo si existe; si no, el documento.
        // El JwtFilter puede resolver el usuario por cualquiera de los dos.
        String subject = (user.getCorreo() != null && !user.getCorreo().isBlank())
                ? user.getCorreo()
                : user.getDocumento();

        // Pares estación+línea aérea activos del usuario: lista vacía = Administrador Global.
        List<ScopeEstacionLinea> scopes = resolverScopes(user.getId());
        List<Long> estacionIds = scopes.stream()
                .map(ScopeEstacionLinea::estacionId).distinct().toList();

        String token = jwtUtil.generateToken(subject, user.getRol().name(), scopes);
        boolean lineaAereaFija = scopes.size() == 1 && scopes.get(0).lineaAereaId() != null;
        Long estacionFijaId = lineaAereaFija ? scopes.get(0).estacionId() : null;
        Long lineaAereaFijaId = lineaAereaFija ? scopes.get(0).lineaAereaId() : null;
        return new AuthResponse(token, user.getRol().name(), user.getNombre(), user.getApellido(),
                jwtExpiration, estacionIds, lineaAereaFija, estacionFijaId, lineaAereaFijaId);
    }

    /**
     * ANTES devolvía solo `List<Long> estacionIds` (findEstacionIdsActivasByUsuarioId).
     * AHORA arma los pares estación+línea aérea desde `usuario_estacion`,
     * que ya incluye la columna `linea_aerea_id` (nullable = todas las
     * líneas de esa estación).
     */
    private List<ScopeEstacionLinea> resolverScopes(Long usuarioId) {
        return usuarioEstacionRepository.findActivasByUsuarioId(usuarioId).stream()
                .map(ue -> new ScopeEstacionLinea(
                        ue.getEstacion().getId(),
                        ue.getLineaAerea() != null ? ue.getLineaAerea().getId() : null))
                .toList();
    }

    /**
     * Mantiene compatibilidad con el AuthRequest original (correo + password).
     * Delega en login(LoginRequest) construyendo el nuevo DTO.
     */
    @Override
    public AuthResponse login(AuthRequest request) {
        return login(new LoginRequest(request.correo(), null, request.password()));
    }

    @Override
    public AuthResponse refresh(String token) {
        if (!jwtUtil.isTokenValid(token)) throw new AccesoDenegadoException("Token inválido");
        String subject = jwtUtil.extractCorreo(token);
        // El subject puede ser un correo o un documento (agentes sin correo)
        Usuario user = buscarPorSubject(subject);
        // Se recalculan los scopes (no se reutilizan los del token viejo) por si
        // cambiaron entre el login original y este refresh.
        List<ScopeEstacionLinea> scopes = resolverScopes(user.getId());
        List<Long> estacionIds = scopes.stream()
                .map(ScopeEstacionLinea::estacionId).distinct().toList();
        String newToken = jwtUtil.generateToken(subject, user.getRol().name(), scopes);
        boolean lineaAereaFija = scopes.size() == 1 && scopes.get(0).lineaAereaId() != null;
        Long estacionFijaId = lineaAereaFija ? scopes.get(0).estacionId() : null;
        Long lineaAereaFijaId = lineaAereaFija ? scopes.get(0).lineaAereaId() : null;
        return new AuthResponse(newToken, user.getRol().name(), user.getNombre(), user.getApellido(),
                jwtExpiration, estacionIds, lineaAereaFija, estacionFijaId, lineaAereaFijaId);
    }

    // ─── Registro de usuario (primer admin o creación por admin) ─────────────────────
    @Override
    @Transactional
    public UsuarioResponse register(UsuarioRequest request) {
        if(usuarioRepository.count()>0){
            verificarRolAdministrador();
        }
        if (request.correo() != null && !request.correo().isBlank()
                && usuarioRepository.existsByCorreo(request.correo()))
            throw new BadRequestException("Ya existe un usuario con el correo: " + request.correo());
        if (usuarioRepository.existsByCodigoEmpleado(request.codigoEmpleado()))
            throw new BadRequestException("Ya existe un usuario con el código de empleado: " + request.codigoEmpleado());
        if (request.password() == null || request.password().isBlank())
            throw new BadRequestException("La contraseña es obligatoria");

        Usuario u = Usuario.builder()
                .nombre(request.nombre())
                .apellido(request.apellido())
                .correo(request.correo())
                .documento(request.documento())
                .codigoEmpleado(request.codigoEmpleado())
                .rol(request.rol())
                .passwordHash(passwordEncoder.encode(request.password()))
                .estado(1)
                .build();

        Usuario saved = usuarioRepository.save(u);
        return toUsuarioResponse(saved);
    }

    // ─── Recuperación de contraseña (Flujo B) ─────────────────────────────────────

    /**
     * Inicia el flujo de recuperación de contraseña.
     *
     * Flujo B — por correo (autónomo, agente con correo o cualquier otro rol):
     *   - Si llega `correo`: busca por correo, envía código al correo (comportamiento original).
     *   - Si llega `dni`:    busca por documento.
     *     · Si el agente tiene correo → envía el código al correo (mismo mecanismo).
     *     · Si no tiene correo        → lanza excepción con mensaje orientativo
     *       para que el agente contacte al administrador (Flujo A).
     *
     * Siempre responde HTTP 200 para no revelar existencia del usuario.
     */
    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        boolean porDni = request.dni() != null && !request.dni().isBlank();
        boolean porCorreo = request.correo() != null && !request.correo().isBlank();

        if (!porDni && !porCorreo)
            throw new BadRequestException("Debes proporcionar un correo o un documento para recuperar tu contraseña");

        if (porDni) {
            // Flujo por DNI (AGENTE_SAASA)
            usuarioRepository.findByDocumento(request.dni()).ifPresent(user -> {
                if (user.getCorreo() != null && !user.getCorreo().isBlank()) {
                    // Tiene correo → envía código igual que el flujo estándar
                    generarYEnviarCodigo(user);
                }
                // Sin correo → no hace nada aquí; el 200 genérico oculta el caso.
                // El mensaje orientativo se retorna solo si el front detecta DNI sin correo
                // consultando un endpoint dedicado (ver forgotPasswordStatus).
            });
        } else {
            // Flujo estándar por correo
            usuarioRepository.findByCorreo(request.correo()).ifPresent(this::generarYEnviarCodigo);
        }
        // Siempre 200 — no revela si el usuario existe
    }

    /**
     * Endpoint auxiliar (Flujo B / sin correo):
     * Indica si el agente con el DNI dado tiene correo registrado.
     * Permite que el frontend muestre el mensaje correcto sin exponer datos sensibles.
     *
     * Respuestas:
     *  - true  → tiene correo, el código fue enviado.
     *  - false → sin correo, debe contactar al administrador (Flujo A).
     *
     * Nota: retorna false también si el DNI no existe (no revela existencia).
     */
    @Override
    public boolean agentetieneCorreo(String dni) {
        return usuarioRepository.findByDocumento(dni)
                .map(u -> u.getCorreo() != null && !u.getCorreo().isBlank())
                .orElse(false);
    }

    // ─── Reset de contraseña ──────────────────────────────────────────────────────

    /**
     * Completa el cambio de contraseña con el código de verificación.
     *
     * Acepta correo o dni como identificador (el mismo que se usó en forgot-password).
     */
    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        boolean porDni = request.dni() != null && !request.dni().isBlank();
        boolean porCorreo = request.correo() != null && !request.correo().isBlank();

        if (!porDni && !porCorreo)
            throw new BadRequestException("Debes proporcionar un correo o un documento");

        Usuario user = porDni
                ? usuarioRepository.findByDocumento(request.dni())
                .orElseThrow(() -> new BadRequestException("Operación no válida"))
                : usuarioRepository.findByCorreo(request.correo())
                .orElseThrow(() -> new BadRequestException("Operación no válida"));

        // Buscar código no usado — por correo si lo tiene, por id si no
        CodigoVerificacion cv = obtenerCodigoValido(user);

        if (cv.getExpiresAt().isBefore(DateTimeUtil.ahoraEnLima()))
            throw new BadRequestException("El código ha expirado");
        if (!cv.getCodigo().equals(request.codigo()))
            throw new BadRequestException("Código incorrecto");

        cv.setUsado(true);
        codigoVerificacionRepository.save(cv);

        user.setPasswordHash(passwordEncoder.encode(request.nuevaPassword()));
        usuarioRepository.save(user);

        // Confirmación por correo solo si el agente tiene correo registrado
        if (user.getCorreo() != null && !user.getCorreo().isBlank()) {
            emailService.enviarConfirmacionReset(user.getCorreo(), user.getNombre(), request.nuevaPassword());
        }
    }

    @Override
    public void verificarRolAdministrador() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccesoDenegadoException("Se requiere autenticación para registrar usuarios");
        }
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMINISTRADOR"));
        if (!isAdmin) {
            throw new AccesoDenegadoException("Solo el ADMINISTRADOR puede registrar nuevos usuarios");
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────────────

    /**
     * Resuelve el usuario por correo (prioritario) o por DNI.
     * Lanza BadRequestException con mensaje genérico si no existe.
     */
    private Usuario resolverUsuarioPorIdentificador(String correo, String dni) {
        if (correo != null && !correo.isBlank()) {
            return usuarioRepository.findByCorreo(correo)
                    .orElseThrow(() -> new BadRequestException("Credenciales inválidas"));
        }
        if (dni != null && !dni.isBlank()) {
            return usuarioRepository.findByDocumento(dni)
                    .orElseThrow(() -> new BadRequestException("Credenciales inválidas"));
        }
        throw new BadRequestException("Debes proporcionar correo o DNI para iniciar sesión");
    }

    /** Determina si el request de login usó el DNI como identificador. */
    private boolean esLoginPorDni(LoginRequest request) {
        return (request.correo() == null || request.correo().isBlank())
                && request.dni() != null && !request.dni().isBlank();
    }

    /**
     * Resuelve el usuario desde el subject del JWT.
     * El subject puede ser un correo (rol con correo) o un documento (agente sin correo).
     */
    private Usuario buscarPorSubject(String subject) {
        // Intenta primero por correo; si no encuentra, intenta por documento
        return usuarioRepository.findByCorreo(subject)
                .or(() -> usuarioRepository.findByDocumento(subject))
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));
    }

    /** Genera un código de 6 dígitos, lo persiste y lo envía por correo. */
    private void generarYEnviarCodigo(Usuario user) {
        codigoVerificacionRepository.invalidarCodigosPrevios(user.getId());
        String codigo = String.format("%06d", new SecureRandom().nextInt(999999));
        CodigoVerificacion cv = CodigoVerificacion.builder()
                .usuario(user)
                .codigo(codigo)
                .expiresAt(DateTimeUtil.ahoraEnLima().plusMinutes(codeExpirationMinutes))
                .usado(false)
                .creadoEn(DateTimeUtil.ahoraEnLima())
                .build();
        codigoVerificacionRepository.save(cv);
        emailService.enviarCodigoVerificacion(user.getCorreo(), user.getNombre(), codigo, codeExpirationMinutes);
    }

    /**
     * Obtiene el código de verificación vigente para el usuario.
     * Busca por correo si existe, por id si no (agente sin correo).
     */
    private CodigoVerificacion obtenerCodigoValido(Usuario user) {
        if (user.getCorreo() != null && !user.getCorreo().isBlank()) {
            return codigoVerificacionRepository
                    .findTopByUsuarioCorreoAndUsadoFalseOrderByCreadoEnDesc(user.getCorreo())
                    .orElseThrow(() -> new BadRequestException("Código inválido o expirado"));
        }
        return codigoVerificacionRepository
                .findTopByUsuarioIdAndUsadoFalseOrderByCreadoEnDesc(user.getId())
                .orElseThrow(() -> new BadRequestException("Código inválido o expirado"));
    }

    private UsuarioResponse toUsuarioResponse(Usuario u) {
        return new UsuarioResponse(
                u.getId(), u.getNombre(), u.getApellido(), u.getCorreo(),
                u.getDocumento(), u.getCodigoEmpleado(), u.getRol().name(),
                u.getEstado(), u.getCreatedAt()
        );
    }
}

