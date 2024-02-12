import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';

import { AuthService } from '../../core/auth.service';
import { ApiError } from '../../core/models';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card" style="max-width: 420px; margin: 2rem auto;">
      <h1>{{ registering() ? 'Create an account' : 'Sign in' }}</h1>

      @if (error()) {
        <div class="alert alert-error" role="alert">{{ error() }}</div>
      }

      <form (ngSubmit)="submit()">
        @if (registering()) {
          <div class="field">
            <label for="fullName">Your name</label>
            <input id="fullName" name="fullName" [(ngModel)]="fullName" required
                   autocomplete="name" />
          </div>
        }

        <div class="field">
          <label for="email">Email</label>
          <input id="email" name="email" type="email" [(ngModel)]="email" required
                 autocomplete="email" />
        </div>

        <div class="field">
          <label for="password">Password</label>
          <input id="password" name="password" type="password" [(ngModel)]="password"
                 required [autocomplete]="registering() ? 'new-password' : 'current-password'" />
          @if (registering()) {
            <p class="faint" style="margin-top:.35rem">
              At least 12 characters, with an uppercase letter, a lowercase letter
              and a digit.
            </p>
          }
        </div>

        <button class="btn-primary" type="submit" [disabled]="busy()" style="width:100%">
          {{ busy() ? 'Please wait…' : registering() ? 'Create account' : 'Sign in' }}
        </button>
      </form>

      <p class="faint" style="margin-top:1rem; text-align:center">
        <button type="button" class="btn-sm" (click)="toggle()">
          {{ registering() ? 'I already have an account' : 'Create an account instead' }}
        </button>
      </p>

      <hr style="border:0; border-top:1px solid var(--line); margin:1.25rem 0" />

      <p class="faint">
        Demo accounts — password <code>DineHub2024!</code> for all three:
      </p>
      <ul class="faint" style="margin:0; padding-left:1.1rem">
        <li>customer&#64;dinehub.local — customer</li>
        <li>chef&#64;dinehub.local — kitchen</li>
        <li>admin&#64;dinehub.local — admin</li>
      </ul>
    </div>
  `,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected email = '';
  protected password = '';
  protected fullName = '';

  protected readonly registering = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected toggle(): void {
    this.registering.update((value) => !value);
    this.error.set(null);
  }

  protected submit(): void {
    if (!this.email || !this.password) {
      this.error.set('Email and password are required.');
      return;
    }

    this.busy.set(true);
    this.error.set(null);

    const request$ = this.registering()
      ? this.auth.register(this.email, this.password, this.fullName)
      : this.auth.login(this.email, this.password);

    request$.subscribe({
      next: () => {
        this.busy.set(false);
        void this.router.navigate(['/menu']);
      },
      error: (response: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(this.describe(response));
      },
    });
  }

  /**
   * Turns the shared ApiError into something a person can act on.
   *
   * <p>Field violations are surfaced specifically — "must contain a digit" is
   * actionable, "Bad Request" is not.
   */
  private describe(response: HttpErrorResponse): string {
    const body = response.error as ApiError | undefined;

    if (body?.violations?.length) {
      return body.violations.map((v) => `${v.field}: ${v.message}`).join('; ');
    }
    if (body?.message) {
      return body.message;
    }
    if (response.status === 0) {
      return 'Cannot reach the server. Is the platform running?';
    }
    return 'Something went wrong. Please try again.';
  }
}
