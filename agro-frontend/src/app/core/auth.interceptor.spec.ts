import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    sessionStorage.setItem('agro.refresh', 'refresh-1');
    (auth as unknown as { accessToken: string }).accessToken = 'viejo';
  });

  it('agrega el Bearer solo a la API', () => {
    http.get('/api/v1/algo').subscribe();
    http.get('/assets/x.json').subscribe();
    expect(ctrl.expectOne('/api/v1/algo').request.headers.get('Authorization')).toBe('Bearer viejo');
    expect(ctrl.expectOne('/assets/x.json').request.headers.has('Authorization')).toBe(false);
  });

  it('ante 401 renueva UNA vez el token para peticiones concurrentes y las reintenta', () => {
    const resultados: unknown[] = [];
    http.get('/api/v1/a').subscribe((r) => resultados.push(r));
    http.get('/api/v1/b').subscribe((r) => resultados.push(r));
    ctrl.expectOne('/api/v1/a').flush(null, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/b').flush(null, { status: 401, statusText: 'Unauthorized' });

    const refresh = ctrl.match('/api/v1/auth/refresh');
    expect(refresh.length).toBe(1);
    expect(refresh[0].request.body).toEqual({ refreshToken: 'refresh-1' });
    refresh[0].flush({ accessToken: 'nuevo', refreshToken: 'refresh-2', tokenType: 'Bearer', expiresIn: 900, refreshExpiresIn: 1 });

    const a = ctrl.expectOne('/api/v1/a');
    const b = ctrl.expectOne('/api/v1/b');
    expect(a.request.headers.get('Authorization')).toBe('Bearer nuevo');
    a.flush('A');
    b.flush('B');
    expect(resultados).toEqual(['A', 'B']);
    expect(sessionStorage.getItem('agro.refresh')).toBe('refresh-2');
  });

  it('si la renovación falla, cierra la sesión y va al login', () => {
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http.get('/api/v1/a').subscribe({ error: () => {} });
    ctrl.expectOne('/api/v1/a').flush(null, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(navegar).toHaveBeenCalledWith(['/login'], { queryParams: { expirada: 1 } });
    expect(sessionStorage.getItem('agro.refresh')).toBeNull();
    expect(auth.token()).toBeNull();
  });
});

describe('AuthService.puede (misma regla que el backend)', () => {
  const perfil = (nivel: string, permisos: string[]) => ({ nivel, permisos } as never);

  it('exige la opción asignada y, si la opción lo pide, el rol mínimo', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    const auth = TestBed.inject(AuthService);
    auth.perfil.set(perfil('USUARIO', ['VENTAS_POS', 'COMPRAS_REGISTRO']));
    expect(auth.puede('VENTAS_POS')).toBe(true);
    expect(auth.puede('VENTAS_FACTURAS')).toBe(false);      // no asignada
    expect(auth.puede('COMPRAS_REGISTRO')).toBe(false);     // asignada pero requiere supervisor
    auth.perfil.set(perfil('SUPERVISOR', ['COMPRAS_REGISTRO', 'ADMIN_USUARIOS']));
    expect(auth.puede('COMPRAS_REGISTRO')).toBe(true);
    expect(auth.puede('ADMIN_USUARIOS')).toBe(false);       // solo administradores
    auth.perfil.set(perfil('ADMIN', ['*']));
    expect(auth.puede('ADMIN_USUARIOS')).toBe(true);
  });
});
