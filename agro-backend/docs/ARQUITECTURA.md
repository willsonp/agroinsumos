# Arquitectura y plan de migración

## 1. Análisis del sistema actual

| Métrica | Valor |
|---|---|
| Código Java (Swing) | ~274.000 líneas, 238 clases en 20 paquetes |
| Base de datos | MySQL 5.7 `pv_agroinsumos`: 92 tablas, 420 procedimientos almacenados |
| Patrón | `FrmXxx` (JFrame) → `Ctrl_Xxx` (hereda del DAO `Xxx`) → JDBC/`CALL` → MySQL |

**Problemas detectados (y cómo se resuelven):**

| Problema en el legacy | Dónde | Solución en el backend |
|---|---|---|
| **Inyección SQL** en el login y búsquedas (`"... where username='" + user + "'"`) | `VentanaLogin.ingresar`, `Ctrl_Usuario.ListarUsuario` | Spring Data JPA / consultas parametrizadas |
| Contraseñas **SHA-1 sin sal** (`SHA1(clave + idUsuario)`) | `Convertir.Encriptar` | BCrypt(12); migración transparente al primer login |
| Estado de sesión en **variables estáticas** | `Ctrl_Parametros` | JWT por petición (`AuthUser`) |
| Asignación de NCF con `SELECT MAX` + `UPDATE` sin bloqueo ni compañía | proc. `ConsultaNCF_Secuencia` | `SELECT … FOR UPDATE` por compañía y tipo |
| Dinero en `FLOAT(9,2)` | `factura`, `detalle_factura`, … | `NUMERIC(18,2)` + `BigDecimal` |
| Conexión nueva por consulta, sin pool; errores mostrados con `JOptionPane` | `ConexionDB`, todos los `Ctrl_*` | HikariCP; `@RestControllerAdvice` → `application/problem+json` |
| Lógica de negocio en procedimientos almacenados (420) | MySQL | Servicios `@Service` testeables (migración gradual) |
| 18 tablas `tmp*` para reportes | `tmpestado`, `tmpbalanza`, … | Consultas/vistas; **no se migran** |
| Variantes por cliente copiadas (`Rogelio`, `Jimayaco`, `VyM`, `Muebleria`, `odontograma`, `Taller`) | paquetes | Un solo módulo configurable (no migrar si no se usan) |
| JARs dentro de `src/DAO` | `spring-webmvc-portlet-4.0.1.jar` | Dependencias gestionadas por Maven |

## 2. Arquitectura de destino

Monolito modular: un paquete por contexto de negocio, cada uno con `entidad → repositorio → servicio → controlador → dto`.
Los módulos se comunican por **servicios públicos o eventos** (p.ej. `UsuarioCreadoEvent`), nunca accediendo a las tablas de otro módulo.

```
com.agroinsumos.backend
├── common      auditoría (actualizado_en/por), errores ProblemDetail, OpenAPI
├── security    JWT, BCrypt + SHA-1 legacy, refresh tokens, OAuth2, filtros
├── auth        /api/v1/auth  (login, refresh, logout, me, menu, password)
├── usuario     /api/v1/usuarios  (FrmUsuario)
├── menu        main_menu / sub_menu / detalle_opciones_menu
├── compania
└── ecf         /api/v1/ecf  Facturación electrónica DGII
```

## 3. Módulo de seguridad

```
                ┌──────────── POST /auth/login ─────────────┐
 Frontend ──────┤                                           ├──► { accessToken (JWT 15 min),
                └── GET /oauth2/authorization/google ──► Google ──► callback ─┘   refreshToken (7 días, rotativo) }
 Frontend ── Authorization: Bearer <JWT> ──► /api/** (stateless, validación HS256 + issuer)
```

- **JWT** (`sub`, `uid`, `cia`, `roles`): firmado HS256 con `JWT_SECRET`. Para varios microservicios, cambiar a RS256 + JWKS.
- **Roles** = `usuario.nivel`: `A`→ADMIN, `S`→SUPERVISOR, `U`→USUARIO, `O`→OTRO. Se usan con `@PreAuthorize`.
- **Permisos por opción** (`detalle_opciones_menu`): `GET /auth/menu` devuelve el árbol que antes pintaba el `JTree`
  de `MenuPrincipal`; `GET /auth/me` devuelve los códigos de permiso para el frontend.
- **Refresh tokens**: opacos y guardados como SHA-256. Cada uso los rota; si se reutiliza uno ya revocado, se revoca
  toda la sesión (posible robo).
- **Fuerza bruta**: tras 5 intentos fallidos se bloquea 15 minutos (`app.security.login.*`).
- **OAuth2/OIDC (Google; extensible a Microsoft Entra o Keycloak)**: solo **vincula** usuarios existentes por correo
  verificado, nunca auto-registra (es un ERP). Tras el login social redirige a `OAUTH2_REDIRECT_URI#access_token=…`.
- **Contraseñas legacy**: `{sha1legacy}<id>$<sha1>` → al primer login correcto pasan a `{bcrypt}` (sin reset masivo).

## 4. Facturación Electrónica (e-CF DGII)

```
POST /ecf/documentos ─► valida reglas ─► reserva e-NCF (FOR UPDATE) ─► XML ─► firma XMLDSig ─► FIRMADO
                                         └──── todo en una transacción: si falla, el número no se consume ────┘
FIRMADO ─► POST /{id}/enviar (o tarea programada) ─► DGII ─► ENVIADO(trackId) ─► consulta ─► ACEPTADO / ACEPTADO_CONDICIONAL / RECHAZADO
   e-CF 32 < RD$250,000 ─► se envía solo el RFCE (resumen) a fc.dgii.gov.do; el XML completo se conserva
   DGII caída ─► ERROR_ENVIO ─► reintento automático (contingencia)
```

| Implementado | Detalle |
|---|---|
| Tipos | 31, 32, 33, 34, 41, 43, 44 y 45 (46 y 47 se rechazan con `TIPO_ECF_NO_SOPORTADO`) |
| e-NCF | `E` + tipo + 10 dígitos; rangos con vencimiento por compañía (`/ecf/secuencias`) |
| Cálculo | ITBIS 18 % / 16 % / 0 % / exento con `BigDecimal` |
| Firma | XMLDSig enveloped, RSA-SHA256, C14N, X509 en KeyInfo; código de seguridad = 6 primeros caracteres del `SignatureValue` |
| DGII | semilla → firma → token (en caché); recepción e-CF y RFCE; consulta por trackId |
| Timbre | `GET /{id}/timbre` devuelve la URL del QR para la representación impresa |
| Concurrencia | e-NCF sin duplicados (probado con 64 reservas en paralelo); envío "reclamado" para no duplicarlo |

### Antes de producción (obligatorio)
1. **XSD oficiales**: descargar del portal e-CF de la DGII y copiarlos en `src/main/resources/dgii/xsd/`
   (`e-CF-31.xsd`, …, `RFCE-32.xsd`); activar `app.ecf.exigir-xsd=true`. El XML se generó según el formato v1.0,
   pero **no se pudo validar aquí contra los XSD** (sin acceso a dgii.gov.do). Ajustar el orden o los campos que reporte el validador.
2. **Certificado digital** `.p12` de una entidad certificadora autorizada (persona física con delegación en la OFV).
3. **Proceso de certificación DGII** en `CERTECF`: set de pruebas, simulación y validación de la representación impresa.
4. Verificar las URLs de los servicios contra la "Descripción Técnica" vigente (son configurables en `app.ecf.urls`).

### Fase 2 del e-CF (pendiente)
- **Servicios de receptor** (obligatorios para ser emisor electrónico): exponer `/fe/autenticacion`, `/fe/recepcion/api/ecf`
  y `/fe/aprobacioncomercial/api/ecf` para recibir e-CF de proveedores y enviar acuse/aprobación comercial.
- Anulación de rangos de e-NCF (ANECF), tipos 46/47, otras monedas e ISC.
- Integración con `Factura`: `FacturaService` construirá `EmitirEcfRequest` desde factura + detalle_factura.

## 5. Mapa de migración por módulos

| # | Módulo | Tablas MySQL | Formularios Swing | Estado |
|---|---|---|---|---|
| 1 | Seguridad y compañía | usuario, compania, main_menu, sub_menu, detalle_opciones_menu | VentanaLogin, FrmUsuario, FrmCompania, MenuPrincipal | ✅ Fase 1 |
| 2 | e-CF DGII | (nuevas) ecf_secuencia, ecf_documento ← tablancf | — | ✅ Fase 1 |
| 3 | Catálogos | zona, ciudad, unidad, turno, departamento, almacen, vendedor, clientex, proveedor, deta_localizador | FrmCiudad, FrmUnidad, FrmClientes, FrmProveedor, FrmDepartamento | ✅ Fase 2 |
| 4 | Inventario | articulo, invalmacen, precio, ajuste(+detalle), transentsalida(+detalle) | FrmArticulos, FrmAjusteInventario, FrmEntradaYSalida, FrmMovimientoArticulo | ✅ Fase 2 |
| 5 | Ventas / Facturación | factura, detalle_factura, cotizacion, devolucion (+detalles) | FrmFactura, FrmDevolucion | ✅ Fase 2 (**emite e-CF**) |
| 5b | Ventas pendientes | facturapro (conduces), apartado, cuotas/financiamiento | FrmFactura (financiada) | Siguiente |
| 6 | Cuentas por cobrar | facturacredito, reciboingreso, detalle_recibo, notadebito | FrmReciboIngreso, FrmNotaDebitoCXC, FrmConsultaEstadoCuenteCXC, FrmConsultaAntiguedadSaldos | ✅ Fase 3 |
| 7 | Compras / CxP | compra, deta_compra, pagocompra, deta_pago_compra, facturacreditocxp | FrmCompra, FrmPagoCompra, FrmConsultaAntiguedadSaldosCXP | ✅ Fase 3 |
| 7b | CxP pendiente | notacreditocxp, notadebitocxp | FrmNotaCreditoCXP, FrmNotaDebitoCXP | Siguiente |
| 10 | Formatos fiscales + e-CF 41/43 | gastos, tipogastos | FrmFormato606, FrmFormato607, FrmGastos | ✅ Fase 4 |
| 8 | Caja | caja, cuadrecaja, detamonedascuadre, monedas, egresocaja, cajachica | FrmCaja, FrmArqueo, FrmEgresoCaja, FrmIngresoCaja, FrmCuadreCajaChica | ✅ Fase 3 |
| 8b | Banco | banco, transaccionesbancarias, conciliacionbancaria | FrmBancos, FrmTransaccionesBanco, FrmConciliar | Siguiente |
| 9 | Contabilidad | catalogo, entradadiario, transaccionesconta, cntcontabilidad, controlcuenta | Contabilidad/* | |
| — | No migrar | tmp* (18), probando, pruebaclase, clientex | variantes por cliente | Revisar con negocio |

**Migración de datos:** pgloader copia MySQL a un esquema `legacy`, y un script por módulo (`docs/migracion-datos/NN_*.sql`)
transforma los datos al esquema nuevo. `01_seguridad.sql`, `02_catalogos_inventario_ventas.sql` y `03_caja_cxc_compras.sql` están probados con el DDL real del dump; cada uno termina con consultas de control para comparar con el legacy.

## 5.1 Fase 2: Catálogos, Inventario y Facturación

### Decisiones de diseño
| Tema | Legacy | Nuevo |
|---|---|---|
| Existencias | No se guardaban: `ConsultaCantidad_Articulo` sumaba TODO el historial en cada consulta | Tabla `existencia` por almacén + kardex `inventario_movimiento` inmutable, actualizados en la misma transacción (fila bloqueada) |
| Precios | Guardados **con** ITBIS (`base = precio / 1.18`) en `FLOAT` | Guardados **sin** ITBIS con 4 decimales; `preciosIncluyenItbis:true` al crear artículos; el precio final se reproduce exacto |
| Totales | Calculados en el formulario (11.000 líneas en `FrmFactura`) | `PreciosVentaService` + `EcfCalculadora`: la factura y su e-CF cuadran al centavo |
| Numeración | `AUTO_INCREMENT` global y `SELECT MAX(id)+1` | `contador_documento` por compañía, sin huecos ni duplicados |
| NCF | `tablancf` + `ConsultaNcf_Secuencia` | La factura emite su e-CF (31/32/44/45) en la misma transacción |
| Anulación | Cambiar `estado = 'N'` | Con e-CF no se borra: **Nota de Crédito electrónica (34)** código 1 (total) o 3 (parcial) |
| Descuentos | Límite fijo de 5 % para el nivel 'O' | `descuento_maximo` por artículo; cambiar precio o exceder el descuento requiere SUPERVISOR/ADMIN |
| Ajustes/entradas/salidas/transferencias | 3 pares de tablas | `documento_inventario` único: BORRADOR → APLICADO |

### Flujo de una factura (una sola transacción)
```
POST /ventas/facturas
  ├─ cliente activo, lista de precios, almacén (principal por defecto), vendedor (el del usuario)
  ├─ precios / descuentos autorizados  ──► 403 PRECIO_NO_AUTORIZADO / DESCUENTO_NO_AUTORIZADO
  ├─ crédito: días > 0 y límite         ──► 422 SIN_CREDITO / LIMITE_CREDITO_EXCEDIDO
  ├─ número interno (contador)
  ├─ salida de inventario (kardex)      ──► 422 EXISTENCIA_INSUFICIENTE
  └─ e-CF firmado (e-NCF reservado)     ──► 422 SECUENCIA_* / 503 CERTIFICADO_*
  Cualquier error ⇒ rollback total: ni factura, ni movimiento, ni e-NCF consumido.
```

### Endpoints nuevos
| Recurso | Rutas |
|---|---|
| Catálogos | `/api/v1/catalogos/{zonas,ciudades,unidades,turnos,departamentos,almacenes,vendedores,clientes,proveedores}` (GET lista/uno, POST, PUT, DELETE = desactivar) |
| Artículos | `/api/v1/inventario/articulos` + `/codigo/{barra}`, `/{id}/existencias`, `/{id}/kardex` |
| Documentos de inventario | `/api/v1/inventario/documentos` + `/{id}/aplicar`, `/{id}/anular` |
| Facturas | `/api/v1/ventas/facturas` + `/{id}/anular`, `/{id}/devoluciones` |
| Devoluciones | `/api/v1/ventas/devoluciones` |
| Cotizaciones | `/api/v1/ventas/cotizaciones` + `/{id}/facturar`, `/{id}/anular` |

### Puntos a validar con el negocio (marcados "REVISAR" en `02_catalogos_inventario_ventas.sql`)
1. Letra de lista de precios del cliente (`clientex.TipoPrecio`): se asumió A=contado, C=crédito, M=mayor.
2. `detalle_factura.Precio` histórico: se migra tal cual (no se pudo confirmar si incluye ITBIS).
3. Existencia inicial: la fórmula legacy no distingue almacén; todo se carga en el almacén principal.
4. Facturas de contado quedan con `balance` pendiente hasta que el módulo **Caja** registre el cobro (equivale al estado 'T' del legacy).

## 5.2 Fase 3: Caja, Cuentas por Cobrar y Compras

### Caja
- **Sesión por cajero** (`caja_sesion`): una abierta por usuario (índice único parcial). Sin caja abierta no se cobra ni se paga en efectivo, tarjeta o cheque (legacy `verifica_caja_abierta`).
- **Movimientos** (`caja_movimiento`): cobros, ingresos, egresos, pagos a suplidores y anulaciones, por forma de pago. El **cuadre** se calcula en tiempo real.
- **Cierre**: conteo de billetes y monedas (`caja_conteo`), con efectivo esperado, contado y diferencia. Después del cierre no se admiten movimientos ni anulaciones con efectivo de esa caja.
- **Egresos/caja chica** guardan RNC y NCF del gasto (base para el 606). No se puede sacar más efectivo del disponible.
- **Punto de venta** `POST /caja/ventas`: factura + e-CF + recibo en una transacción. Reemplaza el flujo legacy pre-factura (`facturapro`, estado 'T') → cobro.

### Cuentas por Cobrar
- **Recibo** (`recibo`): abona facturas y notas de débito. Admite varias formas de pago a la vez (efectivo, tarjeta, cheque, transferencia, saldo a favor), **retenciones de ITBIS e ISR** y devuelta (solo sobre efectivo). La transferencia no requiere caja abierta.
- **Anulación** (supervisor): revierte balances, saldo a favor y caja (si la sesión sigue abierta).
- **Nota de débito** (mora, intereses): emite **e-CF 33** referenciando la factura.
- **Estado de cuenta**, **pendientes** y **antigüedad de saldos** con consultas directas (sustituyen las tablas `tmp*`).

### Compras / CxP
- **Compra**: el NCF o e-NCF del suplidor es único por suplidor. El **costo al inventario** es (importe − descuento + flete prorrateado) / cantidad; el ITBIS es adelantado y no forma parte del costo. Actualiza el **costo promedio ponderado**. Las retenciones (ITBIS/ISR) reducen lo que se paga.
- **Pago a suplidor**: el efectivo sale de la caja (validando que haya disponible); cheque y transferencia salen del banco.
- **Anular compra** solo sin pagos; revierte el inventario (falla si la mercancía ya se vendió).

### Endpoints nuevos
| Recurso | Rutas |
|---|---|
| Caja | `/api/v1/caja/sesiones/abrir`, `/sesiones/actual` (+`/egresos`, `/ingresos`, `/cerrar`), `/sesiones`, `/sesiones/{id}`, `/denominaciones`, `/ventas` |
| CxC | `/api/v1/cxc/recibos` (+`/{id}/anular`), `/notas-debito`, `/clientes/{id}/pendientes`, `/clientes/{id}/estado-cuenta`, `/antiguedad` |
| Compras / CxP | `/api/v1/compras` (+`/{id}/anular`), `/api/v1/cxp/pagos` (+`/{id}/anular`), `/cxp/proveedores/{id}/pendientes`, `/cxp/antiguedad` |

## 5.3 Fase 4: e-CF 41 / 43 y formatos 606 / 607

### e-CF 41 — Comprobante de Compras
- `POST /compras` con `"emitirComprobanteCompras": true` cuando el suplidor es **informal** (persona física sin NCF). Lo emitimos nosotros, en la misma transacción que la compra.
- En el XML, la sección `Comprador` identifica al **vendedor** (cédula obligatoria). No lleva `TipoIngresos`.
- Retenciones por línea (`<Retencion>`: ITBIS e ISR, repartidas proporcionalmente) y totales `TotalITBISRetenido` / `TotalISRRetencion`.
- Los totales de la compra se toman del mismo cálculo que el e-CF, así que siempre cuadran. El flete va como servicio exento.
- Si hay ISR retenido es obligatorio el `tipoRetencionIsr` (606, campo 17).

### e-CF 43 — Gastos Menores
- `POST /caja/sesiones/actual/egresos` con `"emitirGastoMenor": true` (peajes, parqueos, compras menores sin comprobante).
- Sin sección comprador y sin ITBIS (REVISAR con el contador). El e-NCF queda como NCF del egreso para el 606.

### Formatos 606 y 607 (Norma General 07-2018)
| Endpoint | Resultado |
|---|---|
| `GET /api/v1/fiscal/606?periodo=AAAAMM` | Detalle (23 campos por línea), totales y **advertencias** (compras/egresos sin comprobante, suplidores sin RNC) |
| `GET /api/v1/fiscal/606/txt?periodo=AAAAMM` | Archivo `DGII_F_606_<RNC>_<AAAAMM>.TXT` (`606|RNC|periodo|registros` + líneas separadas por `|`) |
| `GET /api/v1/fiscal/607?periodo=AAAAMM&incluirEcf=true` | Ídem para ventas |
| `GET /api/v1/fiscal/607/txt?...` | `DGII_F_607_<RNC>_<AAAAMM>.TXT` |

**Fuentes del 606:** compras (NCF del suplidor o nuestro e-CF 41; bienes vs. servicios según el tipo de artículo, el flete cuenta como servicio) y egresos de caja con comprobante (incluido el e-CF 43). Forma de pago: crédito = 04; contado según los pagos (01 efectivo, 02 cheque/transferencia, 03 tarjeta, 07 mixto). La fecha de pago es la del último pago.

**Fuentes del 607:** facturas (e-CF y NCF migrados; las facturas legacy anuladas van al 608), notas de crédito (e-CF 34) y notas de débito (e-CF 33) con su NCF modificado. Retenciones de ITBIS e ISR desde los recibos (los recibos anulados no cuentan).
- Venta a **crédito**: se informa como venta a crédito (total − retenciones).
- Venta de **contado**: se desglosa por forma de pago según los recibos (efectivo neto de devuelta, prorrateado si un recibo abona varios documentos). Lo no identificado se presume efectivo.

### Criterios a confirmar con el contador (marcados REVISAR en el código)
1. Si como **emisor electrónico** corresponde reportar los e-CF en el 607 (la DGII ya los recibe): por eso existe `incluirEcf=false`.
2. RNC a reportar en gastos menores (B13/E43): se usa el **RNC de la propia empresa**.
3. Tratamiento de las retenciones de ITBIS e ISR en las formas de pago del 607.
4. Formas de pago de las notas de crédito (se dejan en cero).
5. e-CF 43 sin ITBIS.
6. **Obligatorio:** validar los TXT con la *Herramienta de Pre-validación de Formatos de Envío* de la DGII antes de remitirlos.

## 6. Convenciones (SOLID y buenas prácticas)
- **SRP**: el controlador solo maneja HTTP, el servicio las reglas y el repositorio los datos (antes, `Ctrl_*` mezclaba UI, SQL y reportes).
- **DIP**: los puertos `DgiiClient` y `CertificadoProvider` son interfaces, intercambiables por HSM, Vault o mocks en pruebas.
- **OCP**: un tipo de e-CF o un proveedor OAuth2 nuevo se añade sin tocar los flujos existentes.
- DTO (`record`) en la API; las entidades nunca se exponen. `@Version` para concurrencia optimista.
- Esquema versionado con Flyway (`ddl-auto=validate`); multi-compañía con `compania_id` tomado del JWT.
- Pruebas de integración contra PostgreSQL real (37 pruebas: seguridad, e-CF, ventas, inventario, caja, CxC, compras y formatos 606/607).
- Catálogos con plantilla `CrudService`/`CrudController` (Template Method): un catálogo nuevo = entidad + 3 mapeos.

## 7. Frontend: Angular (`agro-frontend`)

Angular 22 · Material 3 · signals · componentes standalone · carga diferida por pantalla (133–170 KB iniciales comprimidos).

| Pantalla | Reemplaza (Swing) | Endpoints |
|---|---|---|
| Login + login social | VentanaLogin | `/auth/login`, `/auth/refresh`, `/oauth2/authorization/*` |
| Punto de venta | FrmFactura + FrmCaja | `/caja/ventas` (contado), `/ventas/facturas` (crédito) |
| Facturas y detalle (reimprimir, XML, anular, devolución) | FrmFactura, FrmDevolucion | `/ventas/facturas`, `/ventas/devoluciones`, `/ecf/documentos/*` |
| Mi caja / Supervisión | FrmCaja, FrmArqueo, FrmEgresoCaja, FrmIngresoCaja | `/caja/**` |
| Cobros / Estado de cuenta / Antigüedad | FrmReciboIngreso, FrmConsultaEstadoCuenteCXC, FrmConsultaAntiguedadSaldos | `/cxc/**` |
| Artículos + kardex | FrmArticulos, FrmMovimientoArticulo | `/inventario/articulos/**` |
| Compras + pago a suplidor | FrmCompra, FrmPagoCompra | `/compras`, `/cxp/pagos` |
| Clientes, proveedores, catálogos generales | FrmClientes, FrmProveedor, FrmCiudad, FrmUnidad… | `/catalogos/**` |
| e-CF, secuencias, formatos 606/607 | FrmFormato606, FrmFormato607 | `/ecf/**`, `/fiscal/**` |
| Usuarios y permisos | FrmUsuario | `/usuarios/**` |

**Decisiones**
- **Tipos generados del OpenAPI** (`npm run api:gen`). Al hacerlo se detectó y corrigió un bug del backend: springdoc fusionaba los DTO anidados homónimos (`ClienteService.Request`, `ArticuloService.Request`…) en un solo esquema `Request`. Ahora cada uno tiene nombre único (`ClienteRequest`…) y `OpenApiContractTest` lo protege.
- **Menú por permisos:** los códigos de `core/navegacion.ts` coinciden con `sub_menu.codigo` (migración `V8`) y el frontend aplica la misma regla que el servidor (opción asignada + rol mínimo). Ver §8.
- **Totales del POS:** `shared/calculo.ts` replica la regla de `EcfCalculadora` (con pruebas): el total mostrado coincide con el de la factura y el e-CF; el servidor siempre tiene la última palabra.
- **Fuentes e íconos alojados localmente** (sin CDN): el punto de venta funciona aunque falle internet.
- **Verificación:** pruebas unitarias (Vitest: cálculo e interceptor) y recorrido E2E con Playwright (login → caja → POS → cobro → ticket con QR → factura → 607; las 16 pantallas sin errores JS/HTTP; vista móvil).

## 8. Autorización: rol + opción de menú

Toda la API se autoriza en el **servidor** con dos capas complementarias:

| Capa | Pregunta | Origen | Ejemplo |
|---|---|---|---|
| **Opción de menú** | ¿A qué módulo tiene acceso? | `detalle_opciones_menu` (asignado en "Usuarios y permisos") | `@permisos.tiene('VENTAS_FACTURAS')` |
| **Rol** (nivel A/S/U/O) | ¿Qué tan sensible es la operación? | `usuario.nivel` (en el JWT) | anular exige `SUPERVISOR` |

- `PermisoService` (bean `permisos`) lee las opciones de la base **una vez por petición**: retirar una opción tiene **efecto inmediato**, sin esperar a que venza el JWT (probado).
- El **administrador** tiene todas las opciones.
- **Catálogos:** la lectura está abierta a cualquier usuario autenticado (POS, compras y cobros consultan clientes, artículos y proveedores); la escritura exige la opción del catálogo (`CrudController.permisoEscritura()`).
- **Red de seguridad:** `CoberturaAutorizacionTest` recorre todos los endpoints de `/api/v1` y falla si alguno no declara `@PreAuthorize` (salvo excepciones justificadas: login/refresh, perfil propio, lectura de catálogos).
- Nota: Spring valida el cuerpo (`@Valid`) **antes** que `@PreAuthorize`; una petición sin permiso con cuerpo inválido recibe 400 en vez de 403 (nunca se ejecuta la operación).

| Opción | Endpoints | Rol mínimo |
|---|---|---|
| `VENTAS_POS` | `POST /caja/ventas`, `POST /ventas/facturas`, cotizaciones, `GET /caja/sesiones/actual`, timbre e-CF | — |
| `VENTAS_FACTURAS` | consultar facturas, devoluciones, XML/estado del e-CF | anular y devolver: **supervisor** |
| `CAJA_SESION` | abrir, egresos, ingresos, cerrar mi caja | — |
| `CAJA_SESIONES` | listado y arqueo de todas las cajas | **supervisor** |
| `CXC_RECIBOS` | cobros, pendientes, notas de débito | anular recibo, emitir nota de débito: **supervisor** |
| `CXC_ESTADO_CUENTA` | estado de cuenta | antigüedad: **supervisor** |
| `INV_ARTICULOS` | crear/editar artículos, kardex | documentos de inventario: **supervisor** |
| `COMPRAS_REGISTRO` | compras, pagos a suplidores, CxP | **supervisor** |
| `CAT_CLIENTES` / `CAT_PROVEEDORES` / `CAT_GENERALES` | escritura del catálogo | — |
| `ECF_DOCUMENTOS` | listado, envío y consulta en la DGII | emisión directa: **admin** |
| `ECF_SECUENCIAS` | ver secuencias | **supervisor**; registrar rangos: **admin** |
| `FISCAL_FORMATOS` | 606 / 607 | **supervisor** |
| `ADMIN_USUARIOS` | usuarios y permisos | **admin** (evita que alguien se auto-eleve) |

**Perfil típico de cajero:** `VENTAS_POS` + `CAJA_SESION` (+ `CXC_RECIBOS` si cobra cuentas a crédito).
