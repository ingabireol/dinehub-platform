import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, signal } from '@angular/core';

import { ApiService } from '../../core/api.service';
import { KitchenTicket } from '../../core/models';

@Component({
  selector: 'app-kitchen',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="between" style="margin-bottom:1.25rem">
      <h1>Kitchen board</h1>
      <span class="faint">Refreshes every 4 seconds</span>
    </div>

    @if (error()) {
      <div class="alert alert-error" role="alert">{{ error() }}</div>
    }

    <div class="grid" style="grid-template-columns: repeat(auto-fit, minmax(300px, 1fr))">
      @for (column of columns(); track column.status) {
        <section>
          <h2>
            {{ column.label }}
            <span class="faint">({{ column.tickets.length }})</span>
          </h2>

          @if (column.tickets.length === 0) {
            <p class="faint">Nothing here.</p>
          }

          <div class="stack">
            @for (ticket of column.tickets; track ticket.id) {
              <article class="card">
                <div class="between">
                  <span class="chip chip-{{ ticket.status }}">{{ ticket.status }}</span>
                  <span class="faint">{{ waitLabel(ticket) }}</span>
                </div>

                <p style="margin:.6rem 0 .3rem"><strong>{{ ticket.itemsSummary }}</strong></p>
                <p class="faint">Order {{ ticket.orderId.slice(0, 8) }}</p>

                @if (ticket.status === 'QUEUED') {
                  <button class="btn-primary btn-sm" type="button"
                          (click)="advance(ticket, 'PREPARING')">Start cooking</button>
                } @else if (ticket.status === 'PREPARING') {
                  <button class="btn-primary btn-sm" type="button"
                          (click)="advance(ticket, 'READY')">Mark ready</button>
                } @else if (ticket.status === 'READY') {
                  <button class="btn-primary btn-sm" type="button"
                          (click)="advance(ticket, 'DELIVERED')">Hand over</button>
                }
              </article>
            }
          </div>
        </section>
      }
    </div>
  `,
})
export class KitchenComponent implements OnDestroy {
  private readonly api = inject(ApiService);

  protected readonly tickets = signal<KitchenTicket[]>([]);
  protected readonly error = signal<string | null>(null);

  /** Three columns, in the order work actually moves. */
  protected readonly columns = computed(() => [
    {
      status: 'QUEUED',
      label: 'Waiting',
      tickets: this.tickets().filter((t) => t.status === 'QUEUED'),
    },
    {
      status: 'PREPARING',
      label: 'Cooking',
      tickets: this.tickets().filter((t) => t.status === 'PREPARING'),
    },
    {
      status: 'READY',
      label: 'Ready',
      tickets: this.tickets().filter((t) => t.status === 'READY'),
    },
  ]);

  // Faster than the customer view: a chef is looking at this continuously and
  // a four-second lag on a busy board is noticeable.
  private readonly poller = setInterval(() => this.load(), 4000);

  constructor() {
    this.load();
  }

  ngOnDestroy(): void {
    clearInterval(this.poller);
  }

  private load(): void {
    this.api.kitchenBoard().subscribe({
      next: (tickets) => {
        this.tickets.set(tickets);
        this.error.set(null);
      },
      error: () => this.error.set('Could not load the board.'),
    });
  }

  protected advance(
    ticket: KitchenTicket,
    status: 'PREPARING' | 'READY' | 'DELIVERED',
  ): void {
    this.api.advanceTicket(ticket.id, status).subscribe({
      next: () => this.load(),
      error: () => {
        // A 409 here means another chef moved it first. Reloading shows them the
        // current truth, which is more useful than an error they cannot act on.
        this.error.set('Someone else moved that ticket. Refreshing the board.');
        this.load();
      },
    });
  }

  protected waitLabel(ticket: KitchenTicket): string {
    const minutes = Math.floor(ticket.waitingSeconds / 60);
    if (ticket.status === 'QUEUED') {
      return minutes < 1 ? 'just now' : `waiting ${minutes} min`;
    }
    if (ticket.preparationSeconds !== null) {
      return `cooked in ${Math.floor(ticket.preparationSeconds / 60)} min`;
    }
    return `started ${minutes} min ago`;
  }
}
