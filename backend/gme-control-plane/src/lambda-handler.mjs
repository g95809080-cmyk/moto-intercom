export function createLambdaHandler({ control, dispatch, log = console.log }) {
  return async function handler(input, context) {
    if (input.Records) {
      const batchItemFailures = [];
      for (const record of input.Records) {
        try { await dispatch(record); }
        catch { batchItemFailures.push({ itemIdentifier: record.dynamodb.SequenceNumber }); }
      }
      return { batchItemFailures };
    }
    if (input.source === 'aws.events') { await control.sweep(); return { ok: true }; }
    // HTTP API's catch-all route is also named $default. Only a socket event
    // contains connectionId; routeKey alone must never choose this branch.
    const response = input.requestContext?.connectionId
      ? await control.websocket(input) : await control.http(input);
    log(JSON.stringify({ requestId: context?.awsRequestId, component: 'gme-control-plane', status: response.statusCode }));
    return response;
  };
}
