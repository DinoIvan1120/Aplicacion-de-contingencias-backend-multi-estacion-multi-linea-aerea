# SAASA – Sistema de Gestión de Contingencias Aeroportuarias
## Backend API REST — Java 17 + Spring Boot 3.3.4

**Versión:** 1.0.0 | **Autor:** Dino Iván Pérez Vásquez | **Año:** 2026

---

## Stack Tecnológico

| Componente | Tecnología |
|---|---|
| Lenguaje | Java 17 |
| Framework | Spring Boot 3.3.4 |
| Base de datos | MySQL 8 (AWS RDS en producción) |
| ORM | Spring Data JPA + Hibernate |
| Seguridad | Spring Security + JWT (stateless) |
| PDF | iText 8 + ZXing (QR) |
| Excel | Apache POI 5 |
| Correo | JavaMailSender (SMTP) |
| Almacenamiento | AWS S3 (SDK v2) |
| WebSocket | Spring WebSocket + STOMP |
| Documentación | Swagger / OpenAPI 3 |
| Testing | JUnit 5 + Mockito + JaCoCo (cobertura de líneas verificada automáticamente en cada build) |
| Variables ENV | dotenv-java |
| Build | Maven |

---

---

## Cobertura de tests

El proyecto usa **JaCoCo** para medir la cobertura de líneas de forma automática. El build falla (`mvn verify`) si la cobertura cae por debajo del umbral configurado en `pom.xml`.

| Fecha | Cobertura real | Umbral mínimo exigido |
|---|---|---|
| 2026-07-23 | 21% | 20% |
| 2026-08-04 | 39% | 35% |

**Cómo verla localmente:**
```bash
mvn clean verify
```
Luego abre `target/site/jacoco/index.html` para ver el detalle por paquete y clase.

**Hoja de ruta de incremento del umbral** (se actualiza en cada sprint conforme se agregan tests):

| Sprint | Umbral objetivo |
|---|---|
| Actual | 35% |
| Sprint 1 | 45% |
| Sprint 2 | 55% |
| Sprint 3 | 65% |
| Meta a mediano plazo | 80% |

---

## Estructura del proyecto

```
src/
└── main/java/com/saasa/contingencias/
    ├── config/           ← Configuraciones (Security, JWT, S3, WebSocket, Swagger…)
    ├── controller/       ← 8 controllers REST bajo /api/v1/
    ├── domain/
    │   ├── dto/          ← Records Java 17 (request + response)
    │   ├── enumeration/  ← 8 enums del dominio
    │   ├── mapping/      ← Mappers por entidad
    │   ├── model/        ← 10 entidades JPA
    │   └── repository/   ← 10 repositorios Spring Data JPA
    ├── service/          ← 10 interfaces de servicio
    │   └── impl/         ← 10 implementaciones
    └── util/             ← ApiResponse, AppConstants, PnrValidator, IataCodigo, EnvValidator
```

---

## Inicio rápido

### 1. Clonar y configurar variables de entorno

```bash
git clone <repo-url>
cd saasa-contingencias-backend
cp .env.example .env
# Editar .env con tus credenciales
```

### 2. Variables de entorno requeridas (`.env`)

```env
DB_URL=jdbc:mysql://localhost:3306/saasa_contingencias?serverTimezone=UTC
DB_USERNAME=root
DB_PASSWORD=
JWT_SECRET=<mínimo 32 caracteres>
JWT_EXPIRATION=86400000
SPRING_PROFILES_ACTIVE=dev
CORS_ALLOWED_ORIGIN=http://localhost:5173
MAIL_HOST=smtp.saasa.com
MAIL_PORT=587
MAIL_USERNAME=noreply@saasa.com
MAIL_PASSWORD=
VERIFICATION_CODE_EXPIRATION_MINUTES=10
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
AWS_REGION=us-east-1
AWS_S3_BUCKET=saasa-contingencias-pdfs
```

### 3. Crear base de datos

```sql
CREATE DATABASE saasa_contingencias CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 4. Ejecutar

```bash
mvn spring-boot:run
```

La API queda disponible en `http://localhost:8080`  
Swagger UI en `http://localhost:8080/swagger-ui.html`

---

## Perfiles disponibles

| Perfil | DDL | Swagger | Logs |
|---|---|---|---|
| `dev` | update | ✅ | DEBUG |
| `qa` | validate | ✅ | INFO |
| `prd` | none | ❌ | WARN |

Activar con: `SPRING_PROFILES_ACTIVE=dev` en `.env`

---

## Roles y permisos

| Rol | Descripción |
|---|---|
| `ADMINISTRADOR` | Acceso total |
| `LIDER_SAASA` | Gestión de vuelos, recursos y reportes |
| `AGENTE_SAASA` | Atención al pasajero, asignación de servicios |
| `LINEA_AEREA` | Solo lectura de sus vuelos |
| `PROVEEDOR` | Solo lectura de sus atenciones |

---

## Endpoints principales (`/api/v1`)

```
POST   /auth/login                      Público
POST   /auth/forgot-password            Público
POST   /auth/reset-password             Público

POST   /auth/register                   Público solo si no hay usuarios 

GET    /usuarios                        ADMINISTRADOR
POST   /usuarios                        ADMINISTRADOR
PUT    /usuarios/{id}                   ADMINISTRADOR
PATCH  /usuarios/{id}/estado            ADMINISTRADOR

GET    /proveedores                     ADMIN, LIDER
POST   /proveedores                     ADMIN
GET    /proveedores/{id}/servicios      ADMIN, LIDER
POST   /proveedores/{id}/servicios      ADMIN

GET    /vuelos                          ADMIN, LIDER, AGENTE, AEREA
POST   /vuelos                          ADMIN, LIDER
POST   /vuelos/carga-masiva             ADMIN (multipart .xlsx)
PATCH  /vuelos/{id}/anular              ADMIN, LIDER
GET    /vuelos/{id}/recursos            ADMIN, LIDER, AGENTE
POST   /vuelos/{id}/recursos            ADMIN, LIDER

GET    /atenciones                      Todos los roles
POST   /atenciones                      ADMIN, LIDER, AGENTE
POST   /atenciones/batch                AGENTE (N pasajeros)
PATCH  /atenciones/{id}/anular          ADMIN, LIDER
POST   /atenciones/{id}/servicios       AGENTE
POST   /atenciones/{id}/pdf             ADMIN, LIDER, AGENTE
GET    /atenciones/{id}/pdf             Todos
POST   /atenciones/{id}/enviar          ADMIN, LIDER, AGENTE, AEREA

GET    /reportes                        ADMIN, LIDER, AEREA, PROVEEDOR
GET    /reportes/excel                  ADMIN, LIDER, AEREA, PROVEEDOR
GET    /auditoria                       ADMIN, LIDER
```

---

## WebSocket

- Endpoint STOMP: `ws://localhost:8080/api/ws`
- Topic suscripción: `/topic/atenciones`
- Eventos: `NUEVA_ATENCION`, `ATENCION_ANULADA`

---

## Testing

```bash
mvn clean verify
```

Este comando corre los tests y valida la cobertura contra el umbral configurado en `pom.xml` (ver sección "Cobertura de tests" arriba). Si el umbral no se cumple, el build falla.

---

## Sprints del proyecto

| Sprint | Semanas | Entregables |
|---|---|---|
| Sprint 1 | 1–2 (Feb 2026) | Auth, Usuarios, Proveedores, Vuelos |
| Sprint 2 | 3–4 (Feb–Mar 2026) | Atención pasajero, PDF, Email, S3 |
| Sprint 3 | 5–6 (Mar 2026) | Reportes, Excel, Portal Proveedor |
| Sprint 4 | 7–8 (Mar–Abr 2026) | Dashboard, Auditoría, Anulaciones |
| Sprint 5 | 9–10 (Abr 2026) | Carga masiva, Tests E2E, Producción |
