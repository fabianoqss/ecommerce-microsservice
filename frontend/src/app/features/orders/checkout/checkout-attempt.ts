import { Service } from '@angular/core';

@Service()
export class CheckoutAttempt {
  private attempt: { snapshotKey: string; key: string } | undefined;

  keyFor(snapshotKey: string): string {
    if (this.attempt?.snapshotKey !== snapshotKey) {
      this.attempt = { snapshotKey, key: crypto.randomUUID() };
    }
    return this.attempt.key;
  }

  renew(): void {
    this.attempt = undefined;
  }

  complete(): void {
    this.attempt = undefined;
  }
}
