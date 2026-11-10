# Agro ERP — Gestión Administrativa PV Agroinsumos

Migración del sistema de escritorio (Java Swing + MySQL) a una arquitectura web:

| Proyecto | Tecnología | Descripción |
|---|---|---|
| [`agro-backend`](agro-backend/README.md) | Spring Boot 4 · Java 21 · PostgreSQL 16 | API REST: seguridad JWT/OAuth2, e-CF DGII, ventas, inventario, caja, CxC, compras, 606/607 |
| [`agro-frontend`](agro-frontend/README.md) | Angular 22 · Material 3 | Aplicación web (punto de venta, caja, cobros, fiscal, administración) |

Arquitectura, decisiones y plan de migración: [`agro-backend/docs/ARQUITECTURA.md`](agro-backend/docs/ARQUITECTURA.md).
