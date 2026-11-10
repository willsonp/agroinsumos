import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Requiere sesión iniciada. */
export const autenticadoGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  return auth.autenticado() ? true : inject(Router).createUrlTree(['/login'], { queryParams: { volver: state.url } });
};

/** Requiere el permiso (código de opción de menú) indicado en data.permiso. */
export const permisoGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const permiso = route.data['permiso'] as string | undefined;
  return !permiso || auth.puede(permiso) ? true : inject(Router).createUrlTree(['/inicio']);
};

export const invitadoGuard: CanActivateFn = () =>
  inject(AuthService).autenticado() ? inject(Router).createUrlTree(['/inicio']) : true;
