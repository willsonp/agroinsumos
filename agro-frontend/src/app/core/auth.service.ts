import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, map, of, shareReplay, switchMap, tap } from 'rxjs';
import type { S } from '../api/tipos';
import { OPCIONES } from './navegacion';

type Tokens = S['AuthTokenResponse'];
type Perfil = S['AuthPerfilResponse'];

const CLAVE_REFRESH = 'agro.refresh';
const CLAVE_RECORDAR = 'agro.recordar';

/**
 * Sesión del usuario. El access token (JWT, 15 min) vive solo en memoria; el refresh token
 * (rotativo) en sessionStorage, o en localStorage si el usuario marca "Mantener sesión".
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private accessToken: string | null = null;
  private refrescando$: Observable<string> | null = null;

  readonly perfil = signal<Perfil | null>(null);
  readonly autenticado = computed(() => this.perfil() !== null);
  readonly esAdmin = computed(() => this.perfil()?.nivel === 'ADMIN');
  readonly esSupervisor = computed(() => ['ADMIN', 'SUPERVISOR'].includes(this.perfil()?.nivel ?? ''));

  token(): string | null {
    return this.accessToken;
  }

  /**
   * ¿Puede usar la opción? Misma regla que el backend: tenerla asignada (el administrador tiene
   * todas) y, si la opción lo exige, el nivel mínimo (supervisor o administrador).
   */
  puede(codigo: string): boolean {
    const p = this.perfil();
    if (!p) return false;
    const permisos = p.permisos ?? [];
    if (!permisos.includes('*') && !permisos.includes(codigo)) return false;
    const rol = OPCIONES[codigo]?.rol;
    if (rol === 'ADMIN') return p.nivel === 'ADMIN';
    if (rol === 'SUPERVISOR') return p.nivel === 'ADMIN' || p.nivel === 'SUPERVISOR';
    return true;
  }

  login(username: string, password: string, recordar: boolean): Observable<Perfil> {
    localStorage.setItem(CLAVE_RECORDAR, String(recordar));
    return this.http.post<Tokens>('/api/v1/auth/login', { username, password }).pipe(
      tap((t) => this.guardar(t)),
      switchMap(() => this.cargarPerfil()),
    );
  }

  /** Tokens recibidos del flujo OAuth2 (fragmento de la URL de retorno). */
  aceptarTokensOAuth(access: string, refresh: string): Observable<Perfil> {
    this.guardar({ accessToken: access, refreshToken: refresh } as Tokens);
    return this.cargarPerfil();
  }

  /** Al iniciar la app: intenta recuperar la sesión con el refresh token guardado. */
  restaurar(): Observable<boolean> {
    if (!this.refreshGuardado()) return of(false);
    return this.refrescar().pipe(
      switchMap(() => this.cargarPerfil()),
      map(() => true),
      catchError(() => {
        this.limpiar();
        return of(false);
      }),
    );
  }

  cargarPerfil(): Observable<Perfil> {
    return this.http.get<Perfil>('/api/v1/auth/me').pipe(tap((p) => this.perfil.set(p)));
  }

  /** Renueva el access token. Peticiones concurrentes comparten la misma renovación. */
  refrescar(): Observable<string> {
    if (!this.refrescando$) {
      const refreshToken = this.refreshGuardado();
      this.refrescando$ = this.http.post<Tokens>('/api/v1/auth/refresh', { refreshToken }).pipe(
        tap((t) => this.guardar(t)),
        map((t) => t.accessToken!),
        finalize(() => (this.refrescando$ = null)),
        shareReplay(1),
      );
    }
    return this.refrescando$;
  }

  logout(): void {
    const refreshToken = this.refreshGuardado();
    if (refreshToken) this.http.post('/api/v1/auth/logout', { refreshToken }).subscribe({ error: () => {} });
    this.limpiar();
    this.router.navigate(['/login']);
  }

  /** Sesión inválida (refresh rechazado): limpiar sin llamar al backend. */
  expirar(): void {
    this.limpiar();
    this.router.navigate(['/login'], { queryParams: { expirada: 1 } });
  }

  private guardar(t: Tokens): void {
    this.accessToken = t.accessToken ?? null;
    if (t.refreshToken) this.almacen().setItem(CLAVE_REFRESH, t.refreshToken);
  }

  private limpiar(): void {
    this.accessToken = null;
    this.perfil.set(null);
    sessionStorage.removeItem(CLAVE_REFRESH);
    localStorage.removeItem(CLAVE_REFRESH);
  }

  private refreshGuardado(): string | null {
    return sessionStorage.getItem(CLAVE_REFRESH) ?? localStorage.getItem(CLAVE_REFRESH);
  }

  private almacen(): Storage {
    return localStorage.getItem(CLAVE_RECORDAR) === 'true' ? localStorage : sessionStorage;
  }
}
