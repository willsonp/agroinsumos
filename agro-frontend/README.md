# agro-frontend

Frontend web (Angular 22 · Angular Material 3 · signals · componentes standalone) del sistema de
Gestión Administrativa PV Agroinsumos. Consume la API REST de `agro-backend`.

## Desarrollo

```bash
# 1) Backend en http://localhost:8080 (ver agro-backend/README.md)
# 2) Frontend con proxy hacia el backend (proxy.conf.json):
npm install
npm start            # http://localhost:4200  ·  admin / Admin123! (perfil dev del backend)
```

| Script | Uso |
|---|---|
| `npm start` | Servidor de desarrollo con proxy `/api` → `localhost:8080` |
| `npm run build` | Build de producción en `dist/agro-frontend/browser` |
| `npm test` | Pruebas unitarias (Vitest) |
| `npm run api:gen` | Regenera `src/app/api/schema.ts` desde `/v3/api-docs` del backend (con el backend corriendo) |

> Los tipos de la API **no se escriben a mano**: se generan del contrato OpenAPI. Tras cambiar un DTO
> en el backend, ejecute `npm run api:gen` y el compilador señalará lo que haya que ajustar.

## Estructura

```
src/app/
├── api/            schema.ts (generado), tipos.ts (Pagina<T>, Problema)
├── core/           auth.service (sesión con signals), auth.interceptor (Bearer + renovación),
│                   auth.guards, navegacion.ts (menú y códigos de permiso), notificaciones
├── layout/         shell: barra superior + menú lateral filtrado por permisos
├── shared/         catalogo-crud (CRUD genérico por configuración), selector de cliente,
│                   buscador de artículos (lector de código de barras), QR del timbre, cálculo de totales
└── pages/          login · inicio · ventas (POS, facturas, devoluciones) · caja · cxc · inventario ·
                    compras · catálogos · fiscal (e-CF, secuencias, 606/607) · admin (usuarios y permisos)
```

## Seguridad en el navegador
- **Access token (JWT, 15 min):** solo en memoria.
- **Refresh token rotativo:** en `sessionStorage`, o en `localStorage` si se marca "Mantener sesión".
- **Renovación:** el interceptor renueva el token una sola vez ante un 401 (compartido entre peticiones concurrentes); si la renovación falla, cierra la sesión.
- **Menú:** se muestra según los permisos del usuario (`/auth/me`). El backend valida roles en cada endpoint; los permisos por opción son solo de interfaz (ver ARQUITECTURA §7).

## Producción (mismo dominio que la API)

```nginx
server {
  listen 443 ssl;
  server_name erp.ejemplo.com.do;
  root /var/www/agro-frontend;            # contenido de dist/agro-frontend/browser

  location /api/          { proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
  location /oauth2/       { proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
  location /login/oauth2/ { proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
  location / { try_files $uri $uri/ /index.html; }   # rutas del SPA
}
```
Con el mismo dominio no se necesita CORS. Configure en el backend `OAUTH2_REDIRECT_URI=https://erp.ejemplo.com.do/auth/oauth2/callback`.

## Impresión
El ticket del punto de venta (`ticket-dialog`) está diseñado para impresoras térmicas de 80 mm (72 mm imprimibles).
Al imprimir con un diálogo abierto se imprime solo el diálogo; incluye el QR del timbre e-CF y el código de seguridad.
