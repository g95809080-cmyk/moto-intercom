import { SecretsManagerClient, GetSecretValueCommand } from '@aws-sdk/client-secrets-manager';
import { ApiGatewayManagementApiClient, PostToConnectionCommand } from '@aws-sdk/client-apigatewaymanagementapi';
import { unmarshall } from '@aws-sdk/util-dynamodb';
import { AwsStore } from './aws-store.mjs';
import { createControlPlane } from './control-plane.mjs';
import { createAuthBuffer } from './authbuffer.mjs';
import { createLambdaHandler } from './lambda-handler.mjs';

const store = new AwsStore(process.env.TABLE_NAME);
const secrets = new SecretsManagerClient({});
const socket = new ApiGatewayManagementApiClient({ endpoint: process.env.WS_MANAGEMENT_ENDPOINT });
let cachedKey;
async function issueTicket(grant) {
  const now = Date.now();
  if (!cachedKey || now >= cachedKey.until) {
    const secret = await secrets.send(new GetSecretValueCommand({ SecretId: process.env.GME_APP_KEY_SECRET_ARN }));
    if (typeof secret.SecretString !== 'string') throw new Error('GME secret unavailable');
    cachedKey = { value: secret.SecretString, until: now + 60_000 };
  }
  const appId = Number(process.env.GME_APP_ID), expiresAt = Math.floor(now / 1000) + 300;
  const authBuffer = createAuthBuffer({ ...grant, appId, appKey: cachedKey.value, expiresAt });
  return { ...grant, appId, expiresAt, authBuffer: authBuffer.toString('base64') };
}
const control = createControlPlane({ store, issueTicket });

async function dispatch(record) {
  if (record.eventName !== 'INSERT') return;
  const item = unmarshall(record.dynamodb.NewImage);
  if (item.kind !== 'EVENT') return;
  for (const target of item.targets) {
    const d = await store.get(`DEVICE#${target}`); if (!d || d.revokedAt) continue;
    // Re-check membership when delivering a delayed event after unpairing.
    if (item.event.rideId) {
      const ride = await store.get(`RIDE#${item.event.rideId}`);
      const pair = ride ? await store.get(`PAIR#${ride.pairId}`) : null;
      if (!pair?.members.includes(target) && item.event.type !== 'ride.ended') continue;
    } else if (item.event.type === 'presence.changed') {
      let paired = false;
      for (const pid of d.pairIds) {
        const p = await store.get(`PAIR#${pid}`);
        if (p?.members.includes(item.event.payload.deviceId)) { paired = true; break; }
      }
      if (!paired) continue;
    }
    for (const cid of d.connectionIds) {
      const c = await store.get(`CONN#${cid}`);
      if (!c || c.deviceId !== target || c.registrationId !== d.registrationId || Date.now() - c.lastHeartbeatAt >= 90_000) continue;
      try { await socket.send(new PostToConnectionCommand({ ConnectionId: cid, Data: Buffer.from(JSON.stringify(item.event)) })); }
      catch (error) {
        if (error.name === 'GoneException') await control.websocket({ requestContext: { routeKey: '$disconnect', connectionId: cid } });
        else throw error;
      }
    }
  }
}

export const handler = createLambdaHandler({ control, dispatch });
