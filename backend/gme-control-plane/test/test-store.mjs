import { after } from 'node:test';
import { randomUUID } from 'node:crypto';
import { DynamoDBClient, CreateTableCommand, DeleteTableCommand } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand } from '@aws-sdk/lib-dynamodb';
import { AwsStore } from '../src/aws-store.mjs';
import { MemoryStore } from './memory-store.mjs';

const endpoint = process.env.GME_TEST_DYNAMODB_ENDPOINT;
const cleanups = [];
after(async () => { for (const cleanup of cleanups) await cleanup(); });

export async function createTestStore() {
  if (!endpoint) {
    const store = new MemoryStore();
    store.snapshot = async () => [...store.records.values()].map(r => structuredClone(r));
    return store;
  }
  const url = new URL(endpoint);
  if (url.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) throw new Error('Only a loopback DynamoDB Local endpoint is allowed');
  const client = new DynamoDBClient({ endpoint, region: 'ap-southeast-2',
    credentials: { accessKeyId: 'testOnly', secretAccessKey: 'testOnly' }, maxAttempts: 2 });
  const doc = DynamoDBDocumentClient.from(client, { marshallOptions: { removeUndefinedValues: true } });
  const table = `gme-test-${randomUUID()}`;
  await client.send(new CreateTableCommand({ TableName: table, BillingMode: 'PAY_PER_REQUEST',
    AttributeDefinitions: [{ AttributeName: 'pk', AttributeType: 'S' }, { AttributeName: 'indexPk', AttributeType: 'S' }, { AttributeName: 'indexAt', AttributeType: 'N' }],
    KeySchema: [{ AttributeName: 'pk', KeyType: 'HASH' }],
    GlobalSecondaryIndexes: [{ IndexName: 'DueIndex', KeySchema: [{ AttributeName: 'indexPk', KeyType: 'HASH' }, { AttributeName: 'indexAt', KeyType: 'RANGE' }], Projection: { ProjectionType: 'ALL' } }],
  }));
  cleanups.push(async () => { await client.send(new DeleteTableCommand({ TableName: table })); client.destroy(); });
  const store = new AwsStore(table, doc);
  store.snapshot = async () => {
    const records = []; let cursor;
    do {
      const page = await doc.send(new ScanCommand({ TableName: table, ConsistentRead: true, ExclusiveStartKey: cursor }));
      records.push(...(page.Items ?? [])); cursor = page.LastEvaluatedKey;
    } while (cursor);
    return records;
  };
  return store;
}
