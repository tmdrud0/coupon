import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

http.setResponseCallback(http.expectedStatuses(202));

const accepted = new Counter('async_submit_accepted');
const unexpected = new Counter('async_submit_unexpected');

export const options = {
  vus: Number(__ENV.VUS || 20),
  iterations: Number(__ENV.ITERATIONS || 100),
  thresholds: {
    'checks{phase:submit}': ['rate>0.99'],
    'http_req_failed{phase:submit}': ['rate<0.01'],
    'http_req_duration{phase:submit}': ['max>=0'],
    'http_reqs{phase:submit}': ['count>=0'],
  },
};

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const couponId = __ENV.COUPON_ID || '1';
const mode = __ENV.ASYNC_MODE || 'DirectKafka';
const expectedMode = __ENV.EXPECTED_RESPONSE_MODE || mode;
const endpointMode = mode === 'Outbox' ? 'outbox' : 'kafka';
const sessionsFile = __ENV.SESSIONS_FILE || '/summary/async-sessions.json';
const sessionData = JSON.parse(open(sessionsFile));
const sessions = Array.isArray(sessionData) ? sessionData : sessionData.sessions;

if (!Array.isArray(sessions)) {
  throw new Error(`Sessions file ${sessionsFile} must contain a sessions array`);
}

export default function () {
  const index = exec.scenario.iterationInTest;
  const sessionId = sessions[index];

  if (!sessionId) {
    unexpected.add(1);
    throw new Error(`No prepared session for submission iteration ${index}`);
  }

  const response = http.post(
    `${baseUrl}/api/coupons/${couponId}/issue-requests/${endpointMode}`,
    null,
    {
      headers: { Cookie: `JSESSIONID=${sessionId}` },
      tags: { phase: 'submit', request_type: 'async_submit', async_mode: mode },
    }
  );

  let body = null;
  try {
    body = response.json();
  } catch (_) {
    // Count malformed responses as unexpected below.
  }

  const valid =
    response.status === 202 &&
    body &&
    body.requestId &&
    body.mode === expectedMode &&
    body.status === 'PENDING';

  if (valid) {
    accepted.add(1);
  } else {
    unexpected.add(1);
  }

  check(
    response,
    {
      'submission accepted': () => response.status === 202,
      'submission contract': () => Boolean(valid),
    },
    { phase: 'submit', request_type: 'async_submit', async_mode: mode }
  );
}
