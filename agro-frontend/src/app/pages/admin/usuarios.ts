import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import type { Pagina, S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion, mensajeDeError } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { params } from '../../shared/http';
import { OPCIONES } from '../../core/navegacion';

type Usuario = S['UsuarioResponse'];
const NIVELES = [['ADMIN', 'Administrador'], ['SUPERVISOR', 'Supervisor'], ['USUARIO', 'Usuario (cajero/vendedor)'], ['OTRO', 'Otro']];

@Component({
  selector: 'app-usuarios',
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatTableModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Usuarios y permisos</h1>
        <button mat-flat-button (click)="editar(null)"><span class="material-symbols-outlined">person_add</span> Nuevo usuario</button></div>
      <div class="tarjeta">
        <div class="fila"><mat-form-field style="flex:1"><mat-label>Buscar</mat-label><input matInput [(ngModel)]="q" (keyup.enter)="cargar()" /></mat-form-field></div>
        <div class="tabla-contenedor"><table mat-table [dataSource]="usuarios()">
          <ng-container matColumnDef="username"><th mat-header-cell *matHeaderCellDef>Usuario</th><td mat-cell *matCellDef="let u">{{ u.username }}</td></ng-container>
          <ng-container matColumnDef="nombre"><th mat-header-cell *matHeaderCellDef>Nombre</th><td mat-cell *matCellDef="let u">{{ u.nombre }} {{ u.apellidos }}</td></ng-container>
          <ng-container matColumnDef="nivel"><th mat-header-cell *matHeaderCellDef>Nivel</th><td mat-cell *matCellDef="let u">{{ u.nivel }}</td></ng-container>
          <ng-container matColumnDef="correo"><th mat-header-cell *matHeaderCellDef>Correo (login Google)</th><td mat-cell *matCellDef="let u">{{ u.correo }}</td></ng-container>
          <ng-container matColumnDef="acceso"><th mat-header-cell *matHeaderCellDef>Último acceso</th><td mat-cell *matCellDef="let u">{{ u.ultimoAcceso | date: 'short' }}</td></ng-container>
          <ng-container matColumnDef="acciones"><th mat-header-cell *matHeaderCellDef></th><td mat-cell *matCellDef="let u" style="white-space:nowrap">
            <button mat-button (click)="editar(u)">Editar</button>
            @if (u.nivel !== 'ADMIN') { <button mat-button (click)="permisos(u)">Permisos</button> }
            <button mat-button (click)="password(u)">Contraseña</button>
            @if (u.id !== auth.perfil()?.id) { <button mat-button (click)="desactivar(u)" style="color: var(--mat-sys-error)">Desactivar</button> }
          </td></ng-container>
          <tr mat-header-row *matHeaderRowDef="columnas"></tr>
          <tr mat-row *matRowDef="let r; columns: columnas"></tr>
        </table></div>
      </div>
    </div>`,
})
export class Usuarios {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly columnas = ['username', 'nombre', 'nivel', 'correo', 'acceso', 'acciones'];
  protected readonly usuarios = signal<Usuario[]>([]);
  protected q = '';

  constructor() { this.cargar(); }

  protected cargar(): void {
    this.http.get<Pagina<Usuario>>('/api/v1/usuarios', { params: params({ q: this.q, size: 100 }) }).subscribe((p) => this.usuarios.set(p.content));
  }

  protected editar(u: Usuario | null): void {
    this.dialog.open(UsuarioDialog, { data: u, width: '640px', maxWidth: '95vw' }).afterClosed().subscribe((ok) => ok && this.cargar());
  }

  protected permisos(u: Usuario): void {
    this.dialog.open(PermisosDialog, { data: u, width: '560px', maxWidth: '95vw' });
  }

  protected password(u: Usuario): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Nueva contraseña para ${u.username}`, accion: 'Cambiar',
      mensaje: 'Mínimo 8 caracteres. Se cerrarán las sesiones abiertas del usuario.', pedirMotivo: 'Nueva contraseña' } })
      .afterClosed().subscribe((p?: string) => p && this.http.put(`/api/v1/usuarios/${u.id}/password`, { passwordNueva: p }).subscribe({
        next: () => this.notif.ok('Contraseña actualizada'), error: (e) => this.notif.error(e) }));
  }

  protected desactivar(u: Usuario): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Desactivar a ${u.username}`, peligro: true, accion: 'Desactivar',
      mensaje: 'El usuario no podrá iniciar sesión y se cerrarán sus sesiones.' } })
      .afterClosed().subscribe((ok) => ok && this.http.delete(`/api/v1/usuarios/${u.id}`).subscribe({
        next: () => { this.notif.ok('Usuario desactivado'); this.cargar(); }, error: (e) => this.notif.error(e) }));
  }
}

@Component({
  selector: 'app-usuario-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>{{ usuario ? 'Editar usuario' : 'Nuevo usuario' }}</h2>
    <mat-dialog-content><form [formGroup]="form" class="grid-form">
      <mat-form-field><mat-label>Usuario</mat-label><input matInput formControlName="username" /></mat-form-field>
      <mat-form-field><mat-label>Nivel</mat-label><mat-select formControlName="nivel">
        @for (n of niveles; track n[0]) { <mat-option [value]="n[0]">{{ n[1] }}</mat-option> }</mat-select></mat-form-field>
      <mat-form-field><mat-label>Nombre</mat-label><input matInput formControlName="nombre" /></mat-form-field>
      <mat-form-field><mat-label>Apellidos</mat-label><input matInput formControlName="apellidos" /></mat-form-field>
      <mat-form-field><mat-label>Cédula</mat-label><input matInput formControlName="cedula" placeholder="11 dígitos" /></mat-form-field>
      <mat-form-field><mat-label>Correo (para login con Google)</mat-label><input matInput formControlName="correo" type="email" /></mat-form-field>
      @if (!usuario) { <mat-form-field><mat-label>Contraseña inicial</mat-label><input matInput formControlName="password" type="password" /></mat-form-field> }
    </form>
    @if (error()) { <p class="error-texto">{{ error() }}</p> }</mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="form.invalid" (click)="guardar()">Guardar</button></mat-dialog-actions>`,
})
export class UsuarioDialog {
  protected readonly usuario = inject<Usuario | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<UsuarioDialog>);
  private readonly http = inject(HttpClient);
  protected readonly niveles = NIVELES;
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    username: [this.usuario?.username ?? '', [Validators.required, Validators.pattern(/^[A-Za-z0-9._-]{3,45}$/)]],
    nivel: [this.usuario?.nivel ?? 'USUARIO', Validators.required],
    nombre: [this.usuario?.nombre ?? '', Validators.required], apellidos: [this.usuario?.apellidos ?? ''],
    cedula: [this.usuario?.cedula ?? '', Validators.pattern(/^\d{11}$/)], correo: [this.usuario?.correo ?? '', Validators.email],
    password: ['', this.usuario ? [] : [Validators.required, Validators.minLength(8)]],
  });

  protected guardar(): void {
    const { password, ...v } = this.form.getRawValue();
    const datos = { ...v, cedula: v.cedula || undefined, correo: v.correo || undefined, apellidos: v.apellidos || undefined };
    const req = this.usuario ? this.http.put(`/api/v1/usuarios/${this.usuario.id}`, datos) : this.http.post('/api/v1/usuarios', { datos, password });
    req.subscribe({ next: () => this.ref.close(true), error: (e) => this.error.set(mensajeDeError(e)) });
  }
}

@Component({
  selector: 'app-permisos-dialog',
  imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, FormsModule],
  template: `
    <h2 mat-dialog-title>Permisos de {{ usuario.username }}</h2>
    <mat-dialog-content>
      @for (g of grupos(); track g.modulo) {
        <h4>{{ g.modulo }}</h4>
        @for (o of g.opciones; track o.subMenuId) {
          <div><mat-checkbox [checked]="seleccion().has(o.subMenuId!)" (change)="alternar(o.subMenuId!)">{{ o.nombre }}
            @if (rolDe(o.codigo); as r) { <span class="muted">· requiere {{ r === 'ADMIN' ? 'administrador' : 'supervisor' }}</span> }
          </mat-checkbox></div>
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="guardar()">Guardar permisos</button></mat-dialog-actions>`,
  styles: `h4 { margin: 12px 0 4px; }`,
})
export class PermisosDialog {
  protected readonly usuario = inject<Usuario>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<PermisosDialog>);
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  private readonly opciones = signal<S['MenuPermisoOpcionResponse'][]>([]);
  protected readonly seleccion = signal(new Set<number>());
  protected readonly grupos = computed(() => {
    const g = new Map<string, S['MenuPermisoOpcionResponse'][]>();
    for (const o of this.opciones()) g.set(o.modulo!, [...(g.get(o.modulo!) ?? []), o]);
    return [...g.entries()].map(([modulo, opciones]) => ({ modulo, opciones }));
  });

  constructor() {
    this.http.get<S['MenuPermisoOpcionResponse'][]>(`/api/v1/usuarios/${this.usuario.id}/permisos`).subscribe((o) => {
      this.opciones.set(o);
      this.seleccion.set(new Set(o.filter((x) => x.permitido).map((x) => x.subMenuId!)));
    });
  }

  protected rolDe(codigo?: string): string | undefined { return OPCIONES[codigo ?? '']?.rol; }

  protected alternar(id: number): void {
    const s = new Set(this.seleccion());
    if (s.has(id)) s.delete(id); else s.add(id);
    this.seleccion.set(s);
  }

  protected guardar(): void {
    this.http.put(`/api/v1/usuarios/${this.usuario.id}/permisos`, { subMenuIds: [...this.seleccion()] }).subscribe({
      next: () => { this.notif.ok('Permisos actualizados (aplican en el próximo inicio de sesión)'); this.ref.close(true); },
      error: (e) => this.notif.error(e),
    });
  }
}
