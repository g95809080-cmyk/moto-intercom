export class TransactionConflict extends Error {}

class Transaction {
  reads = new Map();
  writes = new Map();
  constructor(store) { this.store = store; }
  async get(pk) {
    if (this.writes.has(pk)) return structuredClone(this.writes.get(pk));
    if (!this.reads.has(pk)) this.reads.set(pk, await this.store.get(pk));
    return structuredClone(this.reads.get(pk));
  }
  async put(pk, value) { await this.get(pk); this.writes.set(pk, { ...value, pk }); }
  async delete(pk) { await this.get(pk); this.writes.set(pk, undefined); }
}

export async function transact(store, action) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const tx = new Transaction(store);
    const result = await action(tx);
    try { await store.commit(tx.reads, tx.writes); return result; }
    catch (error) {
      if (!(error instanceof TransactionConflict) || attempt === 5) throw error;
    }
  }
}
