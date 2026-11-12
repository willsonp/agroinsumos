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
| 5b | Ventas pendientes | facturapro (conduces), apartado, cuotas/financiamiento | FrmFactura (financiada) | ✅ Fase 8 |
| 6 | Cuentas por cobrar | facturacredito, reciboingreso, detalle_recibo, notadebito | FrmReciboIngreso, FrmNotaDebitoCXC, FrmConsultaEstadoCuenteCXC, FrmConsultaAntiguedadSaldos | ✅ Fase 3 |
| 7 | Compras / CxP | compra, deta_compra, pagocompra, deta_pago_compra, facturacreditocxp | FrmCompra, FrmPagoCompra, FrmConsultaAntiguedadSaldosCXP | ✅ Fase 3 |
| 7b | CxP: notas de suplidores | notacreditocxp, notadebitocxp | FrmNotaCreditoCXP, FrmNotaDebitoCXP | ✅ Fase 7 |
| 10 | Formatos fiscales + e-CF 41/43 | gastos, tipogastos | FrmFormato606, FrmFormato607, FrmGastos | ✅ Fase 4 |
| 8 | Caja | caja, cuadrecaja, detamonedascuadre, monedas, egresocaja, cajachica | FrmCaja, FrmArqueo, FrmEgresoCaja, FrmIngresoCaja, FrmCuadreCajaChica | ✅ Fase 3 |
| 8b | Banco | banco, transaccionesbancarias, conciliacionbancaria | FrmBancos, FrmTransaccionesBanco, FrmConciliar | ✅ Fase 5 |
| 9 | Contabilidad | catalogo, entradadiario, transaccionesconta, cntcontabilidad, controlcuenta | Contabilidad/* | ✅ Fase 6 |
| 10b | Formato 608, moneda extranjera | (nuevas) comprobante_anulado, tasa_cambio | — | ✅ Fases 7 y 9 |
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

## 5.4 Fase 5: Banco
Reemplaza `banco`, `detallebanco`, `transaccionesbancarias` y `conciliacionbancaria` (migración `V10__banco.sql`,
datos con `docs/migracion-datos/04_banco.sql`).

- **Saldo en libros** = saldo inicial + entradas − salidas de los movimientos vigentes (sustituye al proc. `Balance_CuentaBanco`,
  que recalculaba todo el historial). Tipos: depósito, cheque, transferencia enviada/recibida, nota de débito, nota de crédito.
- **Cheques**: numeración automática por cuenta (`proximo_cheque`); si se escribe un número mayor, la secuencia avanza.
  Índice único `(cuenta, número)` aunque el cheque se anule → `CHEQUE_DUPLICADO` (409).
- **Sin sobregiro**: las salidas validan el saldo (`app.banco.permitir-sobregiro=true` lo desactiva). La cuenta se bloquea
  (`SELECT … FOR UPDATE`) durante el registro; las transferencias bloquean ambas cuentas en orden de id.
- **Depósito desde la caja**: registra el depósito y el egreso de efectivo en la caja abierta del usuario; anularlo devuelve el efectivo.
- **Integración**: un pago a suplidor por cheque/transferencia con `cuentaBancariaId` genera el movimiento (el No. de cheque
  vuelve en la referencia de la forma de pago); un recibo por **transferencia** con `cuentaBancariaId` genera la entrada.
  Los cheques recibidos de clientes pasan por la caja y se depositan después. Estos movimientos solo se anulan anulando el
  pago o recibo (`ANULAR_DESDE_ORIGEN`). Sin `cuentaBancariaId` el comportamiento anterior se mantiene (no se toca el banco).
- **Conciliación**: se marcan los movimientos que aparecen en el estado de cuenta; los no marcados quedan en tránsito.
  `saldo conciliado = libros − depósitos en tránsito + cheques/débitos en tránsito`; solo se cierra si es igual al saldo del banco
  (`CONCILIACION_NO_CUADRA`). Después del corte no se registran movimientos con fecha igual o anterior (`PERIODO_CONCILIADO`)
  ni se anulan los conciliados (`MOVIMIENTO_CONCILIADO`: se registra un movimiento inverso).

| Endpoint | Uso |
|---|---|
| `/api/v1/banco/bancos`, `/api/v1/banco/cuentas` | catálogos (CRUD) |
| `GET/POST /api/v1/banco/movimientos`, `POST /movimientos/{id}/anular` | movimientos manuales |
| `POST /api/v1/banco/transferencias` | transferencia entre cuentas propias |
| `POST /api/v1/banco/conciliaciones/calcular` · `POST /conciliaciones` · `GET /cuentas/{id}/conciliaciones` | conciliación |

## 5.5 Fase 6: Contabilidad
Reemplaza `catalogo`, `entradadiario`, `cntcontabilidad`, `transaccionesconta` y los reportes `tmpbalanza` / `tmpestado`
(migración `V11__contabilidad.sql`, datos con `docs/migracion-datos/05_contabilidad.sql`).

- **Catálogo** jerárquico (`cuenta_contable`: código, tipo, grupo, nivel). Solo las cuentas de detalle reciben movimientos;
  al crear una subcuenta, su grupo deja de aceptar movimientos (si no los tiene). `POST /cuentas/plantilla` carga un catálogo base.
- **Partida doble**: todo asiento (`asiento` + `asiento_linea`) se valida: débitos = créditos, cada línea un solo lado,
  cuentas de detalle activas, período abierto. Auxiliar por línea (cliente/proveedor).
- **Asientos automáticos por lotes** (`POST /asientos/generar?desde&hasta[&simular=true]`), idempotentes
  (índice único por documento) y desacoplados de la operación: una cuenta mal configurada nunca impide facturar; el documento
  queda listado como pendiente con el motivo. Cada tipo de documento es una estrategia `FuenteContable` (Open/Closed):

  | Documento | Asiento |
  |---|---|
  | Factura | Dr CxC cliente / Cr Ingresos (cuenta del departamento) / Cr ITBIS por pagar; Dr Costo / Cr Inventario (departamento). Si `DESCUENTOS_VENTAS` está asignada: ingreso bruto y Dr Descuentos en ventas |
  | Devolución (e-CF 34) | Dr Devoluciones en ventas / Dr ITBIS por pagar / Cr CxC; reingreso: Dr Inventario / Cr Costo |
  | Nota de débito (e-CF 33) | Dr CxC / Cr Otros ingresos / Cr ITBIS por pagar |
  | Recibo | Dr Caja (efectivo neto, cheques) / Tarjetas por cobrar / Banco (transferencia) / CxC (saldo a favor) / retenciones sufridas; Cr CxC |
  | Compra | Dr Inventario (departamento; incluye flete) o Gastos (servicios) / Dr ITBIS adelantado; Cr CxP / Cr ITBIS e ISR retenidos por pagar |
  | Pago a suplidor | Dr CxP (pesos históricos) / Cr Caja o Banco (a la tasa del pago); la diferencia a Pérdida o Ganancia cambiaria |
  | Banco (manuales) | depósito: Banco/Caja; ND: Gastos bancarios/Banco; NC: Banco/Ingresos financieros; cheque directo: contrapartida indicada/Banco; transferencia: Banco destino/Banco origen |
  | Gasto de caja (e-CF 43) | Dr Gastos / Dr ITBIS adelantado / Cr Caja |
  | Cierre de caja con diferencia | sobrante: Dr Caja / Cr Sobrantes de caja; faltante: Dr Faltantes de caja / Cr Caja (fecha del cierre) |

  Prioridad de cuentas: la del departamento / cliente / proveedor / cuenta bancaria; si no tiene, la **cuenta por defecto**
  (`cuenta_contable_config`, claves `CAJA`, `CXC_CLIENTES`, `ITBIS_POR_PAGAR`…; las cuentas `compania.cuenta_*` del legado se
  copian aquí). Factura y compra de contado también pasan por CxC/CxP: el recibo o pago las salda.
- **Anulaciones**: un documento anulado ya contabilizado genera un **reverso** en la siguiente corrida (fecha de la corrida,
  nunca antes del original). Los automáticos no se anulan a mano (`ANULAR_DESDE_ORIGEN`); los manuales sí, si el período está abierto.
- **Fecha de inicio contable** (`compania.fecha_inicio_contable`): los documentos anteriores no se contabilizan (su contabilidad
  se migra del sistema anterior con el script 05).
- **Cierre de períodos** mensual (`periodo_contable`): sin asientos, reversos ni anulaciones en meses cerrados; el admin puede reabrir.
- **Cierre del ejercicio** (`POST /cierres-anuales/{año}[?simular=true]`, admin): asiento al 31/12 que salda cada cuenta
  de ingreso, costo y gasto contra `RESULTADOS_ACUMULADOS` (cuenta de capital). Exige que no queden documentos del año sin
  contabilizar y que diciembre esté abierto; luego rechaza asientos con fecha de ese año (`EJERCICIO_CERRADO`). Se reabre
  anulando el cierre (`/anular`) y se puede volver a cerrar. El estado de resultados excluye este asiento.
- **Reportes**: balanza de comprobación (saldo anterior, débitos, créditos, saldo final, acumulados a los grupos), estado de
  resultados (ingresos − costos = utilidad bruta − gastos = utilidad neta), balance general (el resultado de los ejercicios
  aún no cerrados se presenta dentro del capital como "resultado acumulado") y mayor por cuenta con saldo corrido.

**Descuentos en ventas:** con la cuenta `DESCUENTOS_VENTAS` asignada (el catálogo base trae la 4103; la del legado
`compania.DescuentoEnVenta` se copia), el ingreso se registra bruto y el descuento en su cuenta; sin asignar, el ingreso
se registra neto como antes. Las devoluciones se registran netas en Devoluciones en ventas.

**Pendiente de definir con el contador:** si los faltantes de caja se cargan a gasto o a una cuenta por cobrar al cajero
(basta con asignar esa cuenta a `FALTANTES_CAJA`) y si se requiere revaluación mensual (diferencia cambiaria no realizada).

## 5.7 Moneda extranjera
Migración `V14__moneda_extranjera_descuentos.sql`. La contabilidad, el inventario y los formatos fiscales siguen en pesos.

- **Tasas de cambio** (`tasa_cambio`, `/api/v1/monedas/tasas`): pesos por unidad, por moneda y fecha. Un documento usa la tasa
  indicada al registrarlo o la última registrada en su fecha o antes (`TASA_NO_REGISTRADA` si no hay).
- **Compras en otra moneda**: costos, descuentos, flete y retenciones se ingresan en la moneda del suplidor y se convierten a
  pesos a la tasa de la compra (inventario, costo promedio, 606 y asiento en pesos). Se guardan `total_moneda` y
  `balance_moneda`: la deuda se controla en la moneda original. No aplica e-CF 41 (se emite en pesos).
- **Pagos en otra moneda**: aplicaciones y formas en la moneda de las compras (una sola moneda por pago; solo cheque o
  transferencia). Cada aplicación rebaja de la compra los pesos históricos (`monto_dop`, a la tasa de la compra; si salda la
  deuda, todo lo que quede en pesos). **Diferencia cambiaria realizada** = pesos pagados a la tasa del pago − pesos históricos:
  positiva = pérdida (`PERDIDA_CAMBIARIA`), negativa = ganancia (`GANANCIA_CAMBIARIA`). Las notas de suplidores sobre una
  compra en otra moneda se ingresan en esa moneda y ajustan ambos balances.
- **Cuentas bancarias en otra moneda**: saldo, cheques, conciliación y validación de saldo en la moneda de la cuenta; cada
  movimiento guarda su tasa y se contabiliza en pesos. Un pago en dólares puede salir de una cuenta en dólares o en pesos
  (se debita el equivalente); los cobros en pesos y los depósitos desde caja solo van a cuentas en pesos (`CUENTA_OTRA_MONEDA`).
  Transferencias entre monedas convierten con una sola tasa (sin diferencia cambiaria); entre dos monedas extranjeras
  distintas se pasa por una cuenta en pesos.
- **Recomendación contable:** asignar a cada cuenta bancaria en otra moneda su propia cuenta contable.
- **No incluido:** ventas/facturación en otra moneda (los e-CF se emiten en pesos) y revaluación de saldos al cierre de mes.

## 5.6 Fase 7: Formato 608 y notas de suplidores (CxP)
Migración `V12__formato608_notas_proveedor.sql`.

**Notas de crédito / débito de suplidores** (`nota_proveedor`, `/api/v1/cxp/notas`), siempre sobre una compra:
- **Crédito** (NCF B04 / e-CF 34): reduce el balance de la compra (no puede excederlo). Con líneas es una **devolución
  de mercancía**: la cantidad no puede superar lo comprado menos lo ya devuelto; el importe e ITBIS se prorratean de la
  línea de compra y la mercancía sale del inventario al costo final de la compra. Sin líneas: descuento o rebaja (monto e ITBIS).
- **Débito** (B03 / e-CF 33): aumenta el balance (cargos, intereses); la compra vuelve a estar pendiente y se paga con el pago a suplidores.
- Anular: la NC devuelve el balance y reingresa la mercancía; la ND solo si su monto no se ha pagado (`NOTA_PAGADA`).
  Una compra con notas vigentes no se anula (`COMPRA_CON_NOTAS`). NCF duplicado por suplidor: 409.
- **606**: ambas notas se reportan con el NCF de la compra como *NCF modificado* (forma de pago 06 en NC, 04 en ND — REVISAR).
- **Contabilidad** (`NOTA_PROVEEDOR`): NC Dr CxP / Cr Inventario (departamento) / Cr ITBIS adelantado; ND Dr Gastos de compras / Dr ITBIS adelantado / Cr CxP.
- Las notas del sistema anterior no indicaban la compra: `06_notas_proveedor.sql` lista las que tienen balance para registrarlas a mano.

**Formato 608** (`/api/v1/fiscal/608[/txt]`): `608|RNC|AAAAMM|cantidad` y una línea `NCF|AAAAMMDD|tipo` por comprobante.
- Fuente principal: `comprobante_anulado` (`/api/v1/fiscal/anulaciones`), por **fecha de anulación**; tipos 01–10 de la DGII.
  No se acepta un NCF usado en una factura vigente (`NCF_EN_USO`: se emite nota de crédito) ni un e-CF aceptado (`ECF_ACEPTADO`).
- Además: facturas con NCF del sistema anterior anuladas sin nota de crédito (tipo 04, con advertencia) y advertencia por cada
  e-CF rechazado sin anular: en facturación electrónica los e-NCF no usados se anulan en la Oficina Virtual (ANECF).

## 5.8 Fase 8: Conduces, apartados y ventas financiadas
Migración `V15__conduces_apartados_financiamiento.sql`; datos con `07_conduces_cuotas_apartados.sql`.

- **Conduces** (`/api/v1/ventas/conduces`, opción `VENTAS_CONDUCES`): entrega de mercancía sin factura. Valida precios y
  descuentos como la factura y descuenta el inventario al entregar. Uno o varios conduces abiertos del mismo cliente y
  almacén se facturan juntos (e-CF a los precios del conduce, crédito o contado); la factura no vuelve a mover el inventario
  (`factura.desde_conduce`). Anular (supervisor) solo conduces abiertos: la mercancía regresa. Se imprime con firmas.
- **Apartados** (`/api/v1/ventas/apartados`, `VENTAS_APARTADOS`): el cliente separa mercancía con precios fijados. Cada abono
  entra por la caja abierta (o banco) y aumenta su **saldo a favor** (anticipo). Saldado, se factura y se cobra con un recibo
  de saldo a favor (sin caja); la mercancía sale al facturar. Cancelar (supervisor): devolver el efectivo desde la caja o
  dejarlo como saldo a favor. Contabilidad (`ABONO_APARTADO`): Dr Caja/Tarjetas/Banco · Cr CxC del cliente.
- **Ventas financiadas** (`/api/v1/cxc/financiamientos`, `CXC_FINANCIAMIENTO`): plan sobre una factura a crédito con saldo
  (la inicial se cobra antes con un recibo normal). Interés total (%) sobre el saldo → nota de débito e-CF 33 exenta; cuotas
  iguales (la última ajusta) semanales, quincenales o mensuales; la factura y la nota vencen con la última cuota.
  Los cobros siguen siendo recibos a la factura y a la nota: lo cobrado después del plan se aplica a las cuotas en orden
  (cálculo, sin tablas de saldo que mantener). **Mora**: % de lo pendiente de cada cuota vencida tras los días de gracia,
  una vez por cuota, emitida como nota de débito (e-CF 33). Reporte de **cuotas vencidas** por cliente o zona con teléfono.

## 5.9 Etiquetas ZPL y respaldos diarios
Migración `V16__etiquetas_respaldos.sql`.

**Etiquetas** (`/api/v1/inventario/etiquetas`, Inventario → Imprimir etiquetas):
- Tamaños predefinidos (1.5×1, 2×1, 2.25×1.25, 3×2, 4×2 y 4×6 pulg.) o personalizado (15–120 × 10–200 mm); 203, 300 o
  600 dpi (8, 12 o 24 puntos/mm). Cantidad por artículo (1–1000; máximo 5,000 por trabajo, con `^PQ`).
- Contenido: encabezado opcional con los datos de la empresa (nombre comercial, dirección y teléfono, de Administración →
  Datos de la empresa; si dirección y teléfono no caben juntos van en dos líneas), nombre del artículo (2 líneas, se recorta
  con "..."), precio con ITBIS **solo si se marca "Imprimir precio"** (por defecto no, para no reimprimir cuando cambian los
  precios), código de barras y su texto.
- Selección: pestaña con el catálogo (búsqueda, departamento, "solo con existencia", marcar página, cantidad por artículo o
  igual a la existencia) que pasa los marcados a la lista de impresión (`GET /api/v1/inventario/articulos/seleccion`). Código: `codigo_barra`, si no la referencia, si no el número interno; EAN-13 / UPC-A si el dígito verificador es
  válido (`^BE`, `^BU`), si no Code 128 automático (`^BC`). Barras de hasta 0.5 mm por módulo según el ancho disponible.
- `GeneradorZpl` calcula el diseño en puntos y lo convierte a ZPL II (`^CI28` UTF-8, fuente 0, `^FH` para escapar `^ ~ _`).
  El mismo diseño se envía al navegador, que lo dibuja en un canvas (JsBarcode) como **vista previa**.
- Salida: descarga `etiquetas.zpl` o envío RAW por TCP a una impresora registrada (`impresora_etiqueta`, solo puertos
  9100–9109, para que el servidor no se pueda usar para conectarse a otros servicios).

**Respaldos** (`/api/v1/admin/respaldos`, Administración → Respaldos, solo administrador):
- `pg_dump` en texto plano con `--clean --if-exists --no-owner --no-privileges`: estructura y datos, restaurable con
  `psql -d <base> -f BK_<base>_NN.sql`. Nombre `BK_<base>_<día del mes>.sql` como en el sistema anterior (hasta 31 archivos;
  cada día reemplaza el del mismo día del mes anterior). Se escribe en un temporal y se renombra (nunca queda un archivo a medias).
- **Diario automático**: cada hora (minuto 15, hora de RD) se genera el del día si aún no existe, así no se pierde el día
  aunque el servidor estuviera apagado a una hora fija (`app.respaldo.cron`, `"-"` lo desactiva). También "Crear respaldo ahora".
- Carpeta: `app.respaldo.directorio` (`RESPALDO_DIRECTORIO`) o la configurada en pantalla (ruta absoluta con permiso de
  escritura, guardada en `parametro_sistema`). Se listan y descargan los respaldos para copiarlos a otra ubicación.
  Recomendado: una unidad externa o de red, y copiar periódicamente fuera del servidor.
- **Versión de `pg_dump`**: debe ser de la misma versión mayor del servidor o más nueva (PostgreSQL 18 → `pg_dump` 18;
  un `pg_dump` 16 se niega con "server version mismatch"). Si en el servidor hay varias versiones instaladas, indique la ruta
  con `PG_DUMP` (Windows: `C:\Program Files\PostgreSQL\18\bin\pg_dump.exe`; Linux: `/usr/lib/postgresql/18/bin/pg_dump`).
  La pantalla muestra el error `PG_DUMP_VERSION` con esta indicación.
- **Restaurar**: con el `psql` de la misma versión del servidor (`psql -d <base_nueva> -f BK_<base>_NN.sql`).
  Probado en PostgreSQL 18.6: respaldo de 69 MB en < 1 s y restauración idéntica (tablas, facturas, saldos) en 2 s.

## 5.10 Reportes
Migración `V18__reportes.sql` (módulo Reportes con una opción por grupo). Sustituye los reportes Jasper del sistema anterior
que aún no tenían equivalente (los contables, fiscales, de antigüedad, estado de cuenta, cuotas y conciliación ya existen
en sus módulos).

- **Motor genérico** (`reporte/`): cada reporte es una declaración (`Reporte`: código, grupo, filtros, columnas con tipo y si
  totaliza, y su consulta SQL). Los catálogos (`ReportesVentas`, `ReportesCxcCaja`, `ReportesInventario`, `ReportesCompras`)
  solo declaran; filtros opcionales tipados (null = sin filtro), totales y márgenes, CSV e impresión son comunes. Agregar un
  reporte = agregar una declaración (Open/Closed).
- `GET /api/v1/reportes` (los del usuario según sus opciones), `GET /api/v1/reportes/{codigo}?desde&hasta&corte&clienteId&
  vendedorId&departamentoId&almacenId&proveedorId&conExistencia`, `GET /api/v1/reportes/{codigo}/csv` (Excel: UTF-8 con BOM).
- Frontend: un visor por grupo (`/reportes/ventas|cobros|inventario|compras`) con la lista de reportes, los filtros que pide
  cada uno, tabla con totales, encabezado con los datos de la empresa, **Imprimir** (hoja limpia) y **Excel**.

| Grupo | Reportes (equivalente en el sistema anterior) |
|---|---|
| Ventas | ventas por fecha (RptVentasResumidasPorFecha), detalle (RptVentasDetalle), por cliente (RptVentasResumidasPorCliente), comisiones por vendedor (RptComisionVendedor), ventas y utilidad por artículo (FrmConsultaUtilidad), utilidad por departamento (RptUtilidad_Resumen), devoluciones (RptResumenDevolucion) |
| Cobros y caja | cobros por fecha y forma de pago (RptIngresosResumidoPorFecha), cobros detallados (RptIngresosDetallados), facturas y ND vencidas (RptFacturasVencidas), egresos de caja (RptEgresoCaja) |
| Inventario | **existencia de artículos por almacén a una fecha** (RptConsultaArticulosPorAlmacen; se calcula con el kárdex), **listado para toma de inventario** con columnas Físico/Diferencia en blanco (RptListadoArticuloInventario), productos por vencer, inventario valorizado (RptListadoArticuloInventarioXCosto), lista de precios (RptListadoPrecioArticulos), bajo el mínimo, artículos comprados por suplidor (RptArticulosPorSuplidor) |
| Compras | compras por suplidor (RptEstadosTodosSuplidorRangoFecha), compras detalladas (RptCompraCompleta), pagos a suplidores (RptPagosPorFecha), **estado de cuenta de un suplidor** (RptEstadoCuentaSuplidor: saldo anterior + compras, notas y pagos con saldo acumulado; exige elegir el suplidor), **saldos de todos los suplidores** (anterior, cargos, abonos, saldo final) |

Criterios: la venta neta descuenta las devoluciones; la utilidad usa el costo promedio al vender y descuenta lo devuelto;
las comisiones se calculan sobre lo facturado sin ITBIS menos devoluciones (REVISAR si el negocio las paga sobre lo cobrado);
los precios de venta se muestran con ITBIS.

Estado de cuenta de suplidores: cargo = compra (total − retenciones ITBIS/ISR) y nota de débito; abono = nota de crédito y
pago (en pesos, a la tasa del pago); los documentos anulados no aparecen. El saldo final coincide con el balance de las compras.

## 5.11 Caja chica (fondo fijo)
Migración `V19__caja_chica.sql` (opción `CAJA_CHICA` en el menú Caja). Sustituye `cajachica` del sistema anterior.

- **Fondo** (`caja_chica`): nombre, responsable, monto fijo y cuenta contable (por defecto `CAJA_CHICA` → 110103). Al crearlo
  se registra la **apertura**: cheque o transferencia desde una cuenta bancaria en pesos (queda en Banco, origen `CAJA_CHICA`)
  o efectivo de la caja abierta del supervisor (egreso de caja). El monto del fondo no se cambia después.
- **Vales de gasto**: concepto, beneficiario, monto, cuenta de gasto (por defecto `GASTOS_CAJA`). Con NCF exige RNC/cédula,
  valida el formato y que ese NCF del suplidor no esté ya registrado (en caja chica o en compras); entra al **606**
  (forma de pago 01) y su ITBIS se adelanta. No se puede gastar más que el saldo disponible.
- **Anular vale** (supervisor): solo si aún no se repuso; el monto vuelve al saldo.
- **Reposición** (supervisor): repone todos los vales pendientes por cheque, transferencia o efectivo y deja el fondo completo;
  el detalle (vales, ITBIS, firmas) se imprime desde la pantalla.
- **Arqueo**: efectivo contado contra el saldo del sistema; se guarda la diferencia como control.
- **Contabilidad** (`FuenteCajaChica`): vale → Dr gasto + Dr ITBIS adelantado / Cr caja chica; apertura y reposición →
  Dr caja chica / Cr banco o caja; vale anulado → reverso.
- Endpoints: `/api/v1/caja/chica` (GET, POST), `/{id}` (PUT), `/{id}/movimientos`, `/{id}/gastos`, `/movimientos/{id}/anular`,
  `/{id}/reposiciones`, `/reposiciones/{id}`, `/{id}/arqueos`.

**Compatibilidad con PostgreSQL 18** (probado el 10/11/2026 con PostgreSQL 18.6): las 75 pruebas del backend, las
migraciones V1–V19, la migración completa de datos reales (`00_pgloader.load` + scripts 01–08, resultados idénticos a
PostgreSQL 16), autenticación SCRAM-SHA-256, respaldo/restauración y las 37 pantallas. No requiere cambios.

## 5.12 Lotes y fechas de vencimiento
Migración `V20__lotes_vencimiento.sql`. El sistema anterior no lo manejaba.

- **Opcional por artículo**: casilla "Controla fecha de vencimiento (lotes)" (`articulo.maneja_vencimiento`, desmarcada por
  defecto). Los artículos sin la casilla no cambian en nada.
- **Compra**: para esos artículos cada línea exige la fecha de vencimiento (`VENCIMIENTO_REQUERIDO`) y admite el número de
  lote del suplidor; no se acepta un lote ya vencido (`LOTE_VENCIDO`). Una misma compra puede traer varios lotes del mismo
  artículo (una línea por lote).
- **Existencia por lote** (`lote`: artículo, almacén, número, fecha, recibido, existencia) y trazabilidad
  (`lote_movimiento`: qué lote movió cada documento). Todo pasa por `InventarioService.mover` → `LoteService`, así que
  aplica a ventas, conduces, devoluciones, ajustes, entradas/salidas, transferencias, notas de suplidor y anulaciones:
  - salidas por **FEFO** (vence primero, sale primero); las **ventas** dejan para el final los lotes ya vencidos y los
    **ajustes/salidas** (bajas) los toman primero;
  - **reversiones** vuelven al lote de origen: anulación de compra o conduce, devolución de la factura (referencia a la
    factura), devolución al suplidor (referencia a la compra); una **transferencia** recrea el lote con la misma fecha en el
    almacén destino;
  - una entrada sin dato de lote (ajuste por conteo) va al lote "sin fecha" del almacén.
  La existencia que había antes de marcar el artículo no tiene lote (no se rastrea); conviene registrar su lote con un ajuste.
- **Reporte "Productos por vencer"** (Reportes → Inventario): lotes con existencia vencidos, que vencen hoy o en los próximos
  N días (7 por defecto; filtro `DIAS`, 0 = solo vencidos y hoy), por almacén y departamento, con valor al costo.
  Indicador "Por vencer (7 días)" en el inicio. El kárdex del artículo muestra sus lotes con el estado de cada uno.
- `GET /api/v1/inventario/articulos/{id}/lotes`.

## 5.13 Modo claro / oscuro
El tema de Material se genera con `theme-type: color-scheme` (valores `light-dark()`); `TemaService` fija `color-scheme`
en `<html>`: **Claro**, **Oscuro** o **Según el sistema** (botón en la barra superior y menú del usuario). La preferencia se
guarda en el navegador y `index.html` la aplica antes de pintar. Los colores propios (módulos, chips, avisos) tienen su
versión oscura; la impresión, el ticket y la vista previa de etiquetas son siempre claros (papel).

## 5.14 Presentaciones (saco, caja)
Migración `V21__presentaciones.sql`. Un artículo se lleva en su **unidad base** (Libra, Unidad) y puede tener una
**presentación** opcional para comprar y vender: Saco de 100 lb, Caja de 12, etc.

- Ficha del artículo: presentación (unidad), unidades por presentación (factor > 1), precios propios de la presentación
  por lista (vacío = precio base × factor; crédito/mayor sin precio usan el de contado de la presentación) y código de barras
  de la presentación (escanearlo agrega la línea como presentación).
- Cada línea de compra, factura, cotización, conduce, apartado y nota de suplidor guarda la cantidad en la unidad usada y
  su `factor`. El inventario (existencia, kárdex, lotes) se mueve siempre en unidades base: cantidad × factor; el costo de la
  línea de venta es el de la presentación (costo promedio × factor), así la utilidad y los asientos cuadran.
- La factura, el ticket y el e-CF muestran lo vendido ("1 Saco x RD$2,750"; en el e-CF el nombre lleva "(Saco x 100)").
  Las conversiones cotización/conduce/apartado → factura y las devoluciones conservan la presentación de la línea original.
- Compras: al cambiar la línea de Libra a Saco el costo se convierte con el factor; entra al inventario por libra.
- Reportes por artículo (utilidad, compras por suplidor) suman en unidades base.
- Punto de venta: la cantidad admite decimales (p.ej. 15.5 lb); mismo artículo en distinta unidad va en líneas separadas.

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
| `CAJA_CHICA` | consultar fondos, registrar vales y arqueos | crear/editar fondo, anular vale, reponer: **supervisor** |
| `CXC_RECIBOS` | cobros, pendientes, notas de débito | anular recibo, emitir nota de débito: **supervisor** |
| `CXC_ESTADO_CUENTA` | estado de cuenta | antigüedad: **supervisor** |
| `INV_ARTICULOS` | crear/editar artículos, kardex | — |
| `INV_DOCUMENTOS` | ajustes por conteo, entradas, salidas, transferencias | **supervisor** |
| `VENTAS_COTIZACIONES` | cotizaciones (también con `VENTAS_POS` o `VENTAS_FACTURAS`) | — |
| `COMPRAS_REGISTRO` | compras, pagos a suplidores, CxP | **supervisor** |
| `BANCO_CUENTAS` | crear/editar bancos y cuentas bancarias (la lectura es libre: se eligen al pagar/cobrar) | — |
| `BANCO_MOVIMIENTOS` | depósitos, cheques, transferencias, notas de débito/crédito, anulación | **supervisor** |
| `BANCO_CONCILIACION` | conciliación bancaria e historial | **supervisor** |
| `COMPRAS_NOTAS` | notas de crédito / débito de suplidores | **supervisor** |
| `BANCO_TASAS` | registrar / quitar tasas de cambio (la consulta es libre) | **supervisor** |
| `VENTAS_CONDUCES` | conduces y su facturación | anular: **supervisor** |
| `VENTAS_APARTADOS` | apartados y abonos, facturar | cancelar: **supervisor** |
| `CXC_FINANCIAMIENTO` | planes, cuotas vencidas | crear plan, cobrar mora, cancelar: **supervisor** |
| `INV_ETIQUETAS` | etiquetas ZPL (vista previa, .zpl, envío a impresora) e impresoras | — |
| `ADMIN_RESPALDOS` | respaldos de la base de datos y su carpeta | **admin** |
| `ADMIN_EMPRESA` | datos de la empresa (nombre comercial, dirección, teléfono…; el RNC no) | **admin** |
| `REP_VENTAS` / `REP_CXC_CAJA` / `REP_INVENTARIO` / `REP_COMPRAS` | reportes de cada grupo (consulta, impresión, Excel) | **supervisor** |
| `CONT_CATALOGO` | catálogo de cuentas (CRUD), catálogo base, cuentas por defecto | catálogo base y cuentas por defecto: **supervisor**; fecha de inicio: **admin** |
| `CONT_ASIENTOS` | asientos manuales, anulación, generación de asientos automáticos | **supervisor** |
| `CONT_REPORTES` | balanza, estado de resultados, balance general, mayor | **supervisor** |
| `CONT_PERIODOS` | cierre y reapertura de meses | **admin** |
| `CAT_CLIENTES` / `CAT_PROVEEDORES` / `CAT_GENERALES` | escritura del catálogo | — |
| `ECF_DOCUMENTOS` | listado, envío y consulta en la DGII | emisión directa: **admin** |
| `ECF_SECUENCIAS` | ver secuencias | **supervisor**; registrar rangos: **admin** |
| `FISCAL_FORMATOS` | 606 / 607 / 608 y registro de comprobantes anulados | **supervisor** |
| `ADMIN_USUARIOS` | usuarios y permisos | **admin** (evita que alguien se auto-eleve) |

**Perfil típico de cajero:** `VENTAS_POS` + `CAJA_SESION` (+ `CXC_RECIBOS` si cobra cuentas a crédito).

## 9. Seguridad: verificación OWASP (Top 10 2021 / ASVS 4.0)
Revisión del 10/11/2026. Objetivo: **ASVS nivel 2** (aplicación que maneja datos de negocio y fiscales).
Estado: ✅ cumple · ⚠️ pendiente de despliegue/operación · ❌ no cumple.

| OWASP Top 10 | Controles en la aplicación | Estado |
|---|---|---|
| A01 Control de acceso | Toda ruta `/api/**` exige JWT; `anyRequest().denyAll()` para lo no declarado. Cada endpoint declara `@PreAuthorize` (rol + opción de menú) y `CoberturaAutorizacionTest` falla si uno nuevo no lo hace. Los permisos se leen de la base en cada petición. Cada consulta filtra por la compañía del token (`findByIdAndCompaniaId`, `CrudService.cargar`): no se puede leer un documento de otra compañía cambiando el id. **Nuevo:** el JWT se contrasta con la base en cada petición (activo, no bloqueado, mismo nivel y compañía): desactivar o bajar el nivel a un usuario tiene efecto inmediato. | ✅ |
| A02 Fallas criptográficas | Contraseñas con BCrypt (costo 12); los SHA-1 del sistema anterior se migran a BCrypt al primer inicio de sesión. JWT HS256 con secreto ≥ 32 bytes obligatorio. Refresh tokens aleatorios (256 bits) guardados solo como hash SHA-256. HSTS (1 año) cuando se sirve por HTTPS. | ✅ · ⚠️ servir **solo por HTTPS** |
| A03 Inyección | JPA/JdbcTemplate con parámetros en todas las consultas; el SQL dinámico (reportes, fuentes contables) solo concatena constantes del código. XML de la DGII con parser endurecido contra XXE. Angular escapa todo lo que muestra (no se usa `innerHTML` ni `bypassSecurityTrust`). **Nuevo:** CSV/Excel neutraliza fórmulas (`=`, `+`, `-`, `@`). | ✅ |
| A04 Diseño inseguro | Reglas de negocio en el servidor (saldos, límites de crédito, períodos cerrados, conciliaciones, NCF únicos), bloqueo optimista/pesimista en documentos, anulaciones solo por supervisor y con motivo. | ✅ |
| A05 Configuración insegura | Errores sin traza ni mensaje interno (`ProblemDetail`); actuator solo `health,info`; CORS por lista de orígenes; cabeceras `X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'none'`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`. **Nuevo:** ya no hay perfil `dev` por defecto; fuera de `dev`/`test` el servidor **no arranca** con el secreto JWT publicado en el repositorio y advierte si la base usa la contraseña por defecto; Swagger desactivado salvo `API_DOCS=true`. | ✅ · ⚠️ CSP del frontend en el servidor web |
| A06 Componentes vulnerables | Spring Boot 4.0.9, Angular 22, driver PostgreSQL 42.7.14; `npm audit`: 0 vulnerabilidades. **Nuevo:** Dependabot (`.github/dependabot.yml`) revisa Maven y npm cada semana. | ✅ · ⚠️ activar alertas en GitHub |
| A07 Identificación y autenticación | Bloqueo de usuario tras 5 intentos (15 min); mismo mensaje para usuario inexistente o clave errónea; refresh token rotativo con detección de reutilización (revoca la familia); cambio/reseteo de contraseña y desactivación cierran todas las sesiones; token de acceso de 15 min. **Nuevo:** freno por IP (20 fallos / 15 min → 429) y contraseñas de **mínimo 12 caracteres** (ASVS 2.1.1). | ✅ · ⚠️ MFA para administradores (ASVS L2 2.2.x) |
| A08 Integridad de software y datos | e-CF firmados (XMLDSig) y validados contra el XSD; Flyway con migraciones versionadas e inmutables; respaldos escritos en temporal y renombrados. | ✅ |
| A09 Registro y monitoreo | Usuario y fecha en cada documento y anulación; **nuevo:** log `SEGURIDAD` de inicios de sesión correctos y fallidos (usuario, IP), bloqueos, rechazos por IP y reutilización de refresh tokens. | ✅ · ⚠️ conservar/centralizar logs |
| A10 SSRF | El servidor solo abre conexiones a las URLs de la DGII (configuración) y a impresoras ZPL registradas por un administrador, limitadas a los puertos 9100–9109. | ✅ |

**Pendiente para producción (operación, no código):**
1. Servir backend y frontend **solo por HTTPS** (certificado válido) detrás de nginx/IIS con `FORWARD_HEADERS=native`.
2. En el servidor web del frontend: `Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' <url-api>; frame-ancestors 'none'`.
3. `JWT_SECRET` propio (`openssl rand -base64 48`), contraseña fuerte para el usuario de PostgreSQL (SCRAM) y PostgreSQL sin
   acceso desde fuera del servidor.
4. Activar *Dependabot alerts* y *secret scanning* en GitHub (el repositorio es público: nunca subir `.p12` reales, respaldos ni `.env`).
5. Guardar los logs al menos 90 días y revisar los eventos `SEGURIDAD`.
6. Evaluar MFA (TOTP) para administradores y supervisores; es lo que falta para ASVS nivel 2 completo en autenticación.
