import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuthService } from '../../core/auth.service';
import { mensajeDeError } from '../../core/notificacion.service';
import type { S } from '../../api/tipos';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatCheckboxModule, MatProgressBarModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly ruta = inject(ActivatedRoute);

  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly proveedores = signal<S['AuthProveedorOAuth2'][]>([]);
  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', Validators.required],
    password: ['', Validators.required],
    recordar: [false],
  });

  constructor() {
    const q = this.ruta.snapshot.queryParamMap;
    if (q.get('expirada')) this.error.set('Su sesión expiró. Inicie sesión nuevamente.');
    if (q.get('error') === 'usuario_no_registrado') {
      this.error.set('Su cuenta no está registrada. Solicite al administrador que cree su usuario con ese correo.');
    } else if (q.get('error')) {
      this.error.set('No se pudo completar el inicio de sesión con el proveedor externo.');
    }
    inject(HttpClient).get<S['AuthProveedorOAuth2'][]>('/api/v1/auth/oauth2/proveedores')
      .subscribe({ next: (p) => this.proveedores.set(p), error: () => {} });
  }

  protected entrar(): void {
    if (this.form.invalid) return;
    const { username, password, recordar } = this.form.getRawValue();
    this.cargando.set(true);
    this.error.set(null);
    this.auth.login(username, password, recordar).subscribe({
      next: () => this.router.navigateByUrl(this.ruta.snapshot.queryParamMap.get('volver') ?? '/inicio'),
      error: (e) => { this.error.set(mensajeDeError(e, 'No se pudo iniciar sesión')); this.cargando.set(false); },
    });
  }

  protected nombreProveedor(id: string): string {
    return { google: 'Google', microsoft: 'Microsoft' }[id] ?? id;
  }
}
