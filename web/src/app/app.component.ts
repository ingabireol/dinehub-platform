import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth.service';
import { CartService } from './core/cart.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="shell">
      <header class="topbar">
        <a class="brand" routerLink="/menu">DineHub</a>

        <nav>
          <a class="navlink" routerLink="/menu" routerLinkActive="active">Menu</a>

          @if (auth.isCustomer()) {
            <a class="navlink" routerLink="/orders" routerLinkActive="active">My orders</a>
          }
          @if (auth.isKitchen() || auth.isAdmin()) {
            <a class="navlink" routerLink="/kitchen" routerLinkActive="active">Kitchen</a>
          }
          @if (auth.isAdmin()) {
            <a class="navlink" routerLink="/admin" routerLinkActive="active">Admin</a>
          }
        </nav>

        <span class="spacer"></span>

        @if (auth.isCustomer() && cart.count() > 0) {
          <a class="navlink" routerLink="/cart" routerLinkActive="active">
            Cart <span class="badge">{{ cart.count() }}</span>
          </a>
        }

        @if (auth.isLoggedIn()) {
          <span class="faint">{{ auth.user()?.fullName }} · {{ auth.role() }}</span>
          <button class="btn-sm" type="button" (click)="auth.logout()">Sign out</button>
        } @else {
          <a class="navlink" routerLink="/login" routerLinkActive="active">Sign in</a>
        }
      </header>

      <main>
        <router-outlet />
      </main>
    </div>
  `,
})
export class AppComponent {
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);
}
