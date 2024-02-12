import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';

import { ApiService } from '../../core/api.service';
import { ApiError, Category, MenuItem } from '../../core/models';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [DecimalPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Menu administration</h1>

    @if (error()) {
      <div class="alert alert-error" role="alert">{{ error() }}</div>
    }
    @if (notice()) {
      <div class="alert alert-success" role="status">{{ notice() }}</div>
    }

    <section class="card" style="margin-bottom:1.5rem">
      <h2>Add an item</h2>
      <form (ngSubmit)="create()">
        <div class="field">
          <label for="category">Category</label>
          <select id="category" name="category" [(ngModel)]="form.categoryId" required>
            <option value="">Choose a category</option>
            @for (category of categories(); track category.id) {
              <option [value]="category.id">{{ category.name }}</option>
            }
          </select>
        </div>

        <div class="field">
          <label for="name">Name</label>
          <input id="name" name="name" [(ngModel)]="form.name" required maxlength="140" />
        </div>

        <div class="field">
          <label for="description">Description</label>
          <input id="description" name="description" [(ngModel)]="form.description"
                 maxlength="600" />
        </div>

        <div class="row">
          <div class="field" style="flex:1">
            <label for="price">Price</label>
            <input id="price" name="price" type="number" step="0.01" min="0.01"
                   max="10000" [(ngModel)]="form.price" required />
          </div>
          <div class="field" style="flex:1">
            <label for="prep">Preparation minutes</label>
            <input id="prep" name="prep" type="number" min="1" max="240"
                   [(ngModel)]="form.preparationMinutes" required />
          </div>
        </div>

        <button class="btn-primary" type="submit" [disabled]="saving()">
          {{ saving() ? 'Saving…' : 'Add item' }}
        </button>
      </form>
    </section>

    <section class="card">
      <h2>Current menu</h2>

      @if (items().length === 0) {
        <p class="muted">Nothing on the menu.</p>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th>Category</th>
              <th>Price</th>
              <th>Available</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (item of items(); track item.id) {
              <tr>
                <td>{{ item.name }}</td>
                <td class="muted">{{ item.categoryName }}</td>
                <td class="price">{{ item.price | number: '1.2-2' }}</td>
                <td>
                  <span class="chip" [class.chip-READY]="item.available"
                        [class.chip-CANCELLED]="!item.available">
                    {{ item.available ? 'Yes' : 'Sold out' }}
                  </span>
                </td>
                <td>
                  <button class="btn-sm" type="button" (click)="toggle(item)">
                    {{ item.available ? 'Mark sold out' : 'Make available' }}
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
})
export class AdminComponent {
  private readonly api = inject(ApiService);

  protected readonly categories = signal<Category[]>([]);
  protected readonly items = signal<MenuItem[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected form = {
    categoryId: '',
    name: '',
    description: '',
    price: 0,
    preparationMinutes: 15,
  };

  constructor() {
    this.load();
  }

  private load(): void {
    this.api.categories().subscribe({ next: (c) => this.categories.set(c) });
    // false: an admin needs to see sold-out items in order to bring them back.
    this.api.menuItems(false).subscribe({ next: (i) => this.items.set(i) });
  }

  protected create(): void {
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);

    this.api
      .createMenuItem({
        categoryId: this.form.categoryId,
        name: this.form.name,
        description: this.form.description || undefined,
        price: this.form.price,
        preparationMinutes: this.form.preparationMinutes,
      })
      .subscribe({
        next: (created) => {
          this.saving.set(false);
          this.notice.set(`Added ${created.name}.`);
          this.form = {
            categoryId: '',
            name: '',
            description: '',
            price: 0,
            preparationMinutes: 15,
          };
          this.load();
        },
        error: (response: HttpErrorResponse) => {
          this.saving.set(false);
          const body = response.error as ApiError | undefined;
          // Field violations first: "price must be at least 0.01" is actionable,
          // "Bad Request" is not.
          this.error.set(
            body?.violations?.length
              ? body.violations.map((v) => `${v.field}: ${v.message}`).join('; ')
              : (body?.message ?? 'Could not add the item.'),
          );
        },
      });
  }

  protected toggle(item: MenuItem): void {
    this.api.setItemAvailability(item.id, !item.available).subscribe({
      next: () => this.load(),
      error: () => this.error.set('Could not change availability.'),
    });
  }
}
