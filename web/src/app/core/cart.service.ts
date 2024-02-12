import { Injectable, computed, signal } from '@angular/core';

import { CartItem, MenuItem } from './models';

const STORAGE_KEY = 'dinehub.cart';

/**
 * The basket, kept in the browser until it is submitted.
 *
 * <p>Persisted to localStorage so a refresh does not lose it — losing a basket
 * to an accidental reload is the kind of small thing that makes people give up.
 *
 * <p>It stores quantities and item ids. The <em>price</em> is whatever the
 * server says when the order is placed: pricing on the client would mean the
 * displayed total and the charged total could disagree.
 */
@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly items = signal<CartItem[]>(this.restore());

  readonly lines = this.items.asReadonly();
  readonly count = computed(() =>
    this.items().reduce((total, line) => total + line.quantity, 0),
  );

  /** Indicative only. The server's total is authoritative. */
  readonly estimatedTotal = computed(() =>
    this.items().reduce((total, line) => total + line.item.price * line.quantity, 0),
  );

  readonly isEmpty = computed(() => this.items().length === 0);

  add(item: MenuItem, quantity = 1): void {
    this.items.update((lines) => {
      const existing = lines.find((line) => line.item.id === item.id);
      const updated = existing
        ? lines.map((line) =>
            line.item.id === item.id
              ? { ...line, quantity: Math.min(line.quantity + quantity, 50) }
              : line,
          )
        : [...lines, { item, quantity }];
      this.persist(updated);
      return updated;
    });
  }

  setQuantity(itemId: string, quantity: number): void {
    if (quantity <= 0) {
      this.remove(itemId);
      return;
    }
    this.items.update((lines) => {
      const updated = lines.map((line) =>
        line.item.id === itemId ? { ...line, quantity: Math.min(quantity, 50) } : line,
      );
      this.persist(updated);
      return updated;
    });
  }

  remove(itemId: string): void {
    this.items.update((lines) => {
      const updated = lines.filter((line) => line.item.id !== itemId);
      this.persist(updated);
      return updated;
    });
  }

  clear(): void {
    this.items.set([]);
    localStorage.removeItem(STORAGE_KEY);
  }

  /** The shape the orders API expects. */
  toOrderLines(): { menuItemId: string; quantity: number }[] {
    return this.items().map((line) => ({
      menuItemId: line.item.id,
      quantity: line.quantity,
    }));
  }

  private persist(lines: CartItem[]): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(lines));
    } catch {
      // A full or disabled localStorage must not break ordering; the cart just
      // stops surviving a refresh.
    }
  }

  private restore(): CartItem[] {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return [];
    }
    try {
      return JSON.parse(raw) as CartItem[];
    } catch {
      localStorage.removeItem(STORAGE_KEY);
      return [];
    }
  }
}
