import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';

import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { CartService } from '../../core/cart.service';
import { MenuItem } from '../../core/models';

@Component({
  selector: 'app-menu',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="between" style="margin-bottom:1.25rem">
      <h1>Menu</h1>
      @if (auth.isCustomer() && cart.count() > 0) {
        <a class="btn btn-primary" routerLink="/cart">
          View order ({{ cart.count() }})
        </a>
      }
    </div>

    @if (error()) {
      <div class="alert alert-error" role="alert">{{ error() }}</div>
    }

    @if (loading()) {
      <p class="muted">Loading the menu…</p>
    } @else if (items().length === 0) {
      <div class="empty">
        <p>Nothing on the menu yet.</p>
      </div>
    } @else {
      @for (group of grouped(); track group.category) {
        <section style="margin-bottom:2rem">
          <h2>{{ group.category }}</h2>
          <div class="grid">
            @for (item of group.items; track item.id) {
              <article class="card">
                <div class="between">
                  <h3>{{ item.name }}</h3>
                  <span class="price">{{ item.price | number: '1.2-2' }}</span>
                </div>

                @if (item.description) {
                  <p class="muted" style="font-size:.9rem">{{ item.description }}</p>
                }

                <p class="faint">About {{ item.preparationMinutes }} minutes</p>

                @if (!item.available) {
                  <span class="chip chip-CANCELLED">Sold out</span>
                } @else if (auth.isCustomer()) {
                  <button class="btn-primary btn-sm" type="button" (click)="add(item)">
                    Add to order
                  </button>
                } @else if (!auth.isLoggedIn()) {
                  <a class="btn btn-sm" routerLink="/login">Sign in to order</a>
                }
              </article>
            }
          </div>
        </section>
      }
    }
  `,
})
export class MenuComponent {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);

  protected readonly items = signal<MenuItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Grouped for display. The API already returns them in category order. */
  protected readonly grouped = computed(() => {
    const groups = new Map<string, MenuItem[]>();
    for (const item of this.items()) {
      const existing = groups.get(item.categoryName);
      if (existing) {
        existing.push(item);
      } else {
        groups.set(item.categoryName, [item]);
      }
    }
    return [...groups.entries()].map(([category, items]) => ({ category, items }));
  });

  constructor() {
    this.load();
  }

  private load(): void {
    // Staff see everything including sold-out items; customers see what they
    // can actually order.
    const availableOnly = !this.auth.isKitchen() && !this.auth.isAdmin();

    this.api.menuItems(availableOnly).subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the menu. Please try again shortly.');
        this.loading.set(false);
      },
    });
  }

  protected add(item: MenuItem): void {
    this.cart.add(item);
  }
}
