import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';

import { ApiService } from '../../core/api.service';
import { Notification } from '../../core/models';

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="between" style="margin-bottom:1.25rem">
      <h1>Notifications</h1>
      <button type="button" (click)="markAllRead()">Mark all read</button>
    </div>

    @if (items().length === 0) {
      <div class="empty"><p>Nothing here yet.</p></div>
    } @else {
      <div class="stack">
        @for (item of items(); track item.id) {
          <article class="card" [style.opacity]="item.read ? 0.65 : 1">
            <div class="between">
              <strong>{{ item.title }}</strong>
              <span class="faint">{{ item.createdAt | date: 'd MMM, HH:mm' }}</span>
            </div>
            <p class="muted" style="margin:.4rem 0 0">{{ item.message }}</p>
          </article>
        }
      </div>
    }
  `,
})
export class NotificationsComponent {
  private readonly api = inject(ApiService);

  protected readonly items = signal<Notification[]>([]);

  constructor() {
    this.load();
  }

  private load(): void {
    this.api.notifications().subscribe({
      next: (page) => this.items.set(page.content),
    });
  }

  protected markAllRead(): void {
    this.api.markAllRead().subscribe({ next: () => this.load() });
  }
}
