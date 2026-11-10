# agro-backend

API REST (Spring Boot 4 · Java 21 · PostgreSQL 16) que reemplaza la aplicación de escritorio
**PV Agroinsumos** (Java Swing + MySQL). Módulos: **Seguridad (JWT + OAuth2)**, **e-CF DGII**,
**Catálogos**, **Inventario (kardex)**, **Facturación** (facturas, cotizaciones, devoluciones con nota de crédito electrónica),
**Caja** (punto de venta, cuadre y cierre), **Cuentas por Cobrar** y **Compras / Cuentas por Pagar**.

## Ejecutar en local

```bash
docker compose up -d                       # PostgreSQL 16 en localhost:5432 (agrodb / agro / agro)
mvn spring-boot:run                         # perfil "dev": admin / Admin123!, almacén, consumidor final,
                                            # secuencias e-NCF y certificado de PRUEBA para poder facturar
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Pruebas (requieren la BD `agrodb_test`): `mvn test`

```bash
curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"Admin123!"}'
```

## Variables de entorno (producción)

| Variable | Descripción |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (o `prod,oauth2` para login social) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexión PostgreSQL |
| `JWT_SECRET` | **Obligatorio**, ≥ 32 bytes aleatorios (`openssl rand -base64 48`) |
| `CORS_ORIGINS` | Orígenes del frontend Angular, separados por coma (por defecto `http://localhost:4200`) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Login con Google (perfil `oauth2`) |
| `OAUTH2_REDIRECT_URI` | Ruta del frontend que recibe los tokens tras el login social |
| `ECF_AMBIENTE` | `TESTECF` → `CERTECF` → `ECF` (producción) |
| `ECF_CERT_PATH`, `ECF_CERT_PASSWORD` | Certificado digital `.p12` para firmar e-CF |
| `ECF_ENVIO_AUTOMATICO` | `true` para enviar/reintentar e-CF firmados automáticamente |

Documentación de arquitectura y plan de migración: [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md).
