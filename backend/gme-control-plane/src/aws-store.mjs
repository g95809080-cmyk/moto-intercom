import { randomUUID } from 'node:crypto';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, QueryCommand, TransactWriteCommand } from '@aws-sdk/lib-dynamodb';
import { TransactionConflict } from './store.mjs';

export class AwsStore {
  constructor(tableName, client = DynamoDBDocumentClient.from(new DynamoDBClient({}), { marshallOptions: { removeUndefinedValues: true } })) {
    this.table = tableName; this.client = client;
  }
  async get(pk) {
    return (await this.client.send(new GetCommand({ TableName: this.table, Key: { pk }, ConsistentRead: true }))).Item;
  }
  async query(indexPk, before = Number.MAX_SAFE_INTEGER) {
    const result = []; let cursor;
    do {
      const page = await this.client.send(new QueryCommand({ TableName: this.table, IndexName: 'DueIndex',
        KeyConditionExpression: 'indexPk = :p AND indexAt <= :t', ExpressionAttributeValues: { ':p': indexPk, ':t': before }, ExclusiveStartKey: cursor }));
      result.push(...(page.Items ?? [])); cursor = page.LastEvaluatedKey;
    } while (cursor);
    return result;
  }
  async commit(reads, writes) {
    if (!reads.size) return;
    if (reads.size > 100) throw new Error('Transaction read limit exceeded');
    const TransactItems = [...reads].map(([pk, previous]) => {
      const conditional = previous
        ? { ConditionExpression: '#v = :v', ExpressionAttributeNames: { '#v': 'version' }, ExpressionAttributeValues: { ':v': previous.version } }
        : { ConditionExpression: 'attribute_not_exists(pk)' };
      const base = { TableName: this.table, ...conditional };
      if (!writes.has(pk)) return { ConditionCheck: { ...base, Key: { pk } } };
      const next = writes.get(pk);
      if (next === undefined) return { Delete: { ...base, Key: { pk } } };
      return { Put: { ...base, Item: { ...next, pk, version: (previous?.version ?? 0) + 1 } } };
    });
    try { await this.client.send(new TransactWriteCommand({ TransactItems, ClientRequestToken: randomUUID() })); }
    catch (error) {
      if (error.name === 'TransactionCanceledException' && error.CancellationReasons?.some(r => r.Code === 'ConditionalCheckFailed' || r.Code === 'TransactionConflict')) throw new TransactionConflict();
      if (error.name === 'TransactionConflictException') throw new TransactionConflict();
      throw error;
    }
  }
}
