import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

http.setResponseCallback(http.expectedStatuses(200, 409));

const issueSuccess = new Counter('issue_success');
const issueSoldOut = new Counter('issue_sold_out');
const issueAlreadyIssued = new Counter('issue_already_issued');
const issueUnexpected = new Counter('issue_unexpected');

export const options = {
  vus: Number(__ENV.VUS || 100),
  iterations: Number(__ENV.ITERATIONS || 1000),
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{phase:issue}': ['max>=0'],
    'http_req_failed{phase:issue}': ['rate<=1'],
    'http_reqs{phase:issue}': ['count>=0'],
    checks: ['rate>0.99'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID || '1';
const sessionsFile = __ENV.ISSUE_ONLY_SESSIONS_FILE || '/summary/issue-only-sessions.json';
const sessionData = JSON.parse(open(sessionsFile));
const sessions = Array.isArray(sessionData) ? sessionData : sessionData.sessions;

if (!Array.isArray(sessions)) {
  throw new Error(`Issue-only sessions file ${sessionsFile} must contain a sessions array`);
}

export default function () {
  const sessionIndex = exec.scenario.iterationInTest;
  const sessionId = sessions[sessionIndex];

  if (!sessionId) {
    issueUnexpected.add(1);
    throw new Error(`No prepared session for issue iteration ${sessionIndex}`);
  }

  const issue = http.post(`${baseUrl}/api/coupons/${couponId}/issues`, null, {
    headers: { Cookie: `JSESSIONID=${sessionId}` },
    tags: { phase: 'issue', request_type: 'issue' },
  });

  const issueCode = issue.status === 409 ? issue.json('code') : null;
  if (issue.status === 200) {
    issueSuccess.add(1);
  } else if (issueCode === 'SOLD_OUT') {
    issueSoldOut.add(1);
  } else if (issueCode === 'ALREADY_ISSUED') {
    issueAlreadyIssued.add(1);
  } else {
    issueUnexpected.add(1);
  }

  check(
    issue,
    {
      'issue terminal status': () => issue.status === 200 || issueCode === 'SOLD_OUT',
      'no duplicate issue during load': () => issueCode !== 'ALREADY_ISSUED',
    },
    { phase: 'issue', request_type: 'issue' }
  );
}
