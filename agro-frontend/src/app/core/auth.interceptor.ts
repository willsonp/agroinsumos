import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const SIN_TOKEN = ['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout', '/api/v1/auth/oauth2/proveedores'];

/**
 * Agrega "Authorization: Bearer" a las llamadas a la API. Si el backend responde 401 (token
 * vencido), renueva el token UNA vez (compartido entre peticiones concurrentes) y reintenta.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  if (!req.url.startsWith('/api/') || SIN_TOKEN.some((u) => req.url.startsWith(u))) return next(req);

  const conToken = (r: HttpRequest<unknown>, token: string | null) =>
    token ? r.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : r;

  return next(conToken(req, auth.token())).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 401) return throwError(() => err);
      return auth.refrescar().pipe(
        catchError((e) => {
          auth.expirar();
          return throwError(() => e);
        }),
        switchMap((nuevo) => next(conToken(req, nuevo))),
      );
    }),
  );
};
