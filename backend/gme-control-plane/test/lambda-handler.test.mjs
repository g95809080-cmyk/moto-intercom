import test from 'node:test';
import assert from 'node:assert/strict';
import { createLambdaHandler } from '../src/lambda-handler.mjs';

test('HTTP $default 与 WebSocket $default 分流正确；日志不包含请求正文或 token', async () => {
  const calls = [], logs = [];
  const handler = createLambdaHandler({ control: {
    http: async () => { calls.push('http'); return { statusCode: 200 }; },
    websocket: async () => { calls.push('socket'); return { statusCode: 400 }; },
  }, dispatch: async () => {}, log: line => logs.push(JSON.parse(line)) });
  const http = { requestContext: { routeKey: '$default', http: { method: 'POST' } }, body: 'private', headers: { 'x-signature': 'private' } };
  assert.equal((await handler(http, { awsRequestId: 'req' })).statusCode, 200);
  assert.equal((await handler({ requestContext: { routeKey: '$default', connectionId: 'conn' } }, {})).statusCode, 400);
  assert.deepEqual(calls, ['http', 'socket']);
  assert.deepEqual(logs[0], { requestId: 'req', component: 'gme-control-plane', status: 200 });
  assert.equal(JSON.stringify(logs).includes('private'), false);
});

test('outbox 部分失败只返回失败的 stream sequence，成功项不丢失', async () => {
  const processed = [];
  const handler = createLambdaHandler({ control: {}, dispatch: async record => {
    if (record.dynamodb.SequenceNumber === '2') throw new Error('transient');
    processed.push(record.dynamodb.SequenceNumber);
  } });
  assert.deepEqual(await handler({ Records: ['1', '2', '3'].map(SequenceNumber => ({ dynamodb: { SequenceNumber } })) }),
    { batchItemFailures: [{ itemIdentifier: '2' }] });
  assert.deepEqual(processed, ['1', '3']);
});
