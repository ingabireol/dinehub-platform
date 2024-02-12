import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';

import { ApiService } from '../../core/api.service';
import { CartService } from '../../core/cart.service';
import { ApiError } from '../../core/models';

@Component({
  selector: 'app-cart',
  standalone: true,
  imports: [DecimalPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Your order</h1>

    @if (error()) {
      <div class="alert alert-error" role="alert">{{ error() }}</div>
    }

    @if (cart.isEmpty()) {
      <div class="empty">
        <p>Your order is empty.</p>
        <a class="btn btn-primary" routerLink="/menu">Browse the menu</a>
      </div>
    } @else {
      <div class="card" style="margin-bottom:1.25rem">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th>Price</th>
              <th>Quantity</th>
              <th>Total</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (line of cart.lines(); track line.item.id) {
              <tr>
                <td>{{ line.item.name }}</td>
                <td class="price">{{ line.item.price | number: '1.2-2' }}</td>
                <td>
                  <input
                    type="number" min="1" max="50" style="width:5rem"
                    [ngModel]="line.quantity" name="qty-{{ line.item.id }}"
                    (ngModelChange)="cart.setQuantity(line.item.id, +$event)"
                    [attr.aria-label]="'Quantity of ' + line.item.name" />
                </td>
                <td class="price">
                  {{ line.item.price * line.quantity | number: '1.2-2' }}
                </td>
                <td>
                  <button class="btn-sm btn-danger" type="button"
                          (click)="cart.remove(line.item.id)">Remove</button>
                </td>
              </tr>
            }
          </tbody>
        </table>

        <div class="between" style="margin-top:1rem">
          <span class="muted">Estimated total</span>
          <span class="price" style="font-size:1.2rem">
            {{ cart.estimatedTotal() | number: '1.2-2' }}
          </span>
        </div>
        <p class="faint">
          The kitchen confirms the final total when the order is placed.
        </p>
      </div>

      <div class="card">
        <div class="field">
          <label for="address">Delivery address</label>
          <input id="address" name="address" [(ngModel)]="deliveryAddress"
                 placeholder="Where should we bring it?" />
        </div>
        <div class="field">
          <label for="notes">Notes for the kitchen</label>
          <textarea id="notes" name="notes" rows="2" [(ngModel)]="notes"
                    placeholder="Allergies, preferences…"></textarea>
        </div>

        <div class="row">
          <button class="btn-primary" type="button" (click)="placeOrder()"
                  [disabled]="submitting()">
            {{ submitting() ? 'Placing your order…' : 'Place order' }}
          </button>
          <button type="button" (click)="cart.clear()" [disabled]="submitting()">
            Clear
          </button>
        </div>
      </div>
    }
  `,
})
export class CartComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly cart = inject(CartService);

  protected deliveryAddress = '';
  protected notes = '';
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected placeOrder(): void {
    this.submitting.set(true);
    this.error.set(null);

    this.api
      .placeOrder({
        items: this.cart.toOrderLines(),
        deliveryAddress: this.deliveryAddress || undefined,
        notes: this.notes || undefined,
      })
      .subscribe({
        next: () => {
          // Clear only after the server has accepted it. Clearing optimistically
          // would lose the basket when the order is rejected — for example
          // because something sold out while they were deciding.
          this.cart.clear();
          this.submitting.set(false);
          void this.router.navigate(['/orders']);
        },
        error: (response: HttpErrorResponse) => {
          this.submitting.set(false);
          const body = response.error as ApiError | undefined;
          this.error.set(
            body?.message ?? 'Could not place the order. Please try again.',
          );
        },
      });
  }
}
