import { ChangeDetectionStrategy, Component, OnDestroy, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';

import { ApiService } from '../../core/api.service';
import { Order } from '../../core/models';

@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="between" style="margin-bottom:1.25rem">
      <h1>My orders</h1>
      <a class="btn" routerLink="/menu">Order something else</a>
    </div>

    @if (error()) {
      <div class="alert alert-error" role="alert">{{ error() }}</div>
    }

    @if (loading()) {
      <p class="muted">Loading your orders…</p>
    } @else if (orders().length === 0) {
      <div class="empty">
        <p>You have not ordered anything yet.</p>
        <a class="btn btn-primary" routerLink="/menu">Browse the menu</a>
      </div>
    } @else {
      <div class="stack">
        @for (order of orders(); track order.id) {
          <article class="card">
            <div class="between">
              <div>
                <span class="chip chip-{{ order.status }}">{{ order.status }}</span>
                <span class="faint" style="margin-left:.5rem">
                  {{ order.placedAt | date: 'd MMM, HH:mm' }}
                </span>
              </div>
              <span class="price">{{ order.totalAmount | number: '1.2-2' }}</span>
            </div>

            <ul style="margin:.75rem 0; padding-left:1.1rem">
              @for (line of order.items; track line.menuItemId) {
                <li class="muted" style="font-size:.9rem">
                  {{ line.quantity }} × {{ line.itemName }}
                  <span class="faint">({{ line.lineTotal | number: '1.2-2' }})</span>
                </li>
              }
            </ul>

            @if (order.cancellationReason) {
              <p class="faint">{{ order.cancellationReason }}</p>
            }

            @if (order.status === 'PLACED' || order.status === 'PAID') {
              <button class="btn-sm btn-danger" type="button" (click)="cancel(order)">
                Cancel this order
              </button>
              <p class="faint" style="margin-top:.4rem">
                You can cancel until the kitchen starts preparing it.
              </p>
            }
          </article>
        }
      </div>
    }
  `,
})
export class OrdersComponent implements OnDestroy {
  private readonly api = inject(ApiService);

  protected readonly orders = signal<Order[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /**
   * Orders move through their lifecycle asynchronously, so the page polls.
   *
   * <p>Five seconds, and only while the page is open. Server-sent events or a
   * WebSocket would be better; polling is honest about being the simple choice
   * for a handful of orders per customer.
   */
  private readonly poller = setInterval(() => this.load(false), 5000);

  constructor() {
    this.load(true);
  }

  ngOnDestroy(): void {
    clearInterval(this.poller);
  }

  private load(showSpinner: boolean): void {
    if (showSpinner) {
      this.loading.set(true);
    }
    this.api.myOrders().subscribe({
      next: (page) => {
        this.orders.set(page.content);
        this.loading.set(false);
      },
      error: () => {
        // A failed background poll must not replace the orders already on screen
        // with an error — the previous data is still the best thing to show.
        if (showSpinner) {
          this.error.set('Could not load your orders.');
          this.loading.set(false);
        }
      },
    });
  }

  protected cancel(order: Order): void {
    this.api.cancelOrder(order.id, 'Cancelled by the customer').subscribe({
      next: () => this.load(false),
      error: () =>
        this.error.set(
          'That order can no longer be cancelled — the kitchen has started it.',
        ),
    });
  }
}
