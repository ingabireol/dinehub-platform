import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';

import { environment } from '../../environments/environment';
import { Role, TokenResponse, User } from './models';

const TOKEN_KEY = 'dinehub.accessToken';
const REFRESH_KEY = 'dinehub.refreshToken';
const USER_KEY = 'dinehub.user';

/**
 * Authentication state.
 *
 * <p>Tokens live in localStorage. That is a deliberate, stated trade-off: it is
 * vulnerable to XSS, and the alternative — an httpOnly cookie — needs CSRF
 * protection and a same-site deployment, which this architecture does not have.
 * The mitigations are a 15-minute access token and a strict Content-Security-Policy
 * at the Nginx layer.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly currentUser = signal<User | null>(this.restoreUser());

  readonly user = this.currentUser.asReadonly();
  readonly isLoggedIn = computed(() => this.currentUser() !== null);
  readonly role = computed<Role | null>(() => this.currentUser()?.role ?? null);
  readonly isCustomer = computed(() => this.role() === 'CUSTOMER');
  readonly isKitchen = computed(() => this.role() === 'KITCHEN');
  readonly isAdmin = computed(() => this.role() === 'ADMIN');

  login(email: string, password: string): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${environment.apiUrl}/auth/login`, { email, password })
      .pipe(tap((response) => this.store(response)));
  }

  register(email: string, password: string, fullName: string): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${environment.apiUrl}/auth/register`, {
        email,
        password,
        fullName,
      })
      .pipe(tap((response) => this.store(response)));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
    this.currentUser.set(null);
    void this.router.navigate(['/login']);
  }

  get accessToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  private store(response: TokenResponse): void {
    localStorage.setItem(TOKEN_KEY, response.accessToken);
    localStorage.setItem(REFRESH_KEY, response.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(response.user));
    this.currentUser.set(response.user);
  }

  private restoreUser(): User | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw) as User;
    } catch {
      // Corrupt storage should log the user out cleanly, not crash the app on
      // its first render.
      localStorage.removeItem(USER_KEY);
      return null;
    }
  }
}
