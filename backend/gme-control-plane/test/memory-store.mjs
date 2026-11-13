import { TransactionConflict } from '../src/store.mjs';

// The same optimistic transaction boundary as DynamoDB, including read-only checks.
export class MemoryStore {
  records = new Map();
  async get(pk) { return structuredClone(this.records.get(pk)); }
  async query(indexPk, before = Number.MAX_SAFE_INTEGER) {
    return [...this.records.values()]
      .filter(r => r.indexPk === indexPk && r.indexAt <= before)
      .map(r => structuredClone(r));
  }
  async commit(reads, writes) {
    for (const [pk, prior] of reads) {
      if (this.records.get(pk)?.version !== prior?.version) throw new TransactionConflict();
    }
    for (const [pk, next] of writes) {
      if (next === undefined) this.records.delete(pk);
      else this.records.set(pk, structuredClone({ ...next, pk, version: (reads.get(pk)?.version ?? 0) + 1 }));
    }
  }
}
