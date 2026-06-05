import http from 'k6/http';
import { check, sleep } from 'k6';
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
    checks: ['rate>0.99'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID || '1';
const usernamePrefix = __ENV.USERNAME_PREFIX || `load-${Date.now()}`;

export default function () {
  const username = `${usernamePrefix}-${__VU}-${__ITER}`;
  const login = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ username }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(login, {
    'login ok': (response) => response.status === 200,
  });

  const issue = http.post(`${baseUrl}/api/coupons/${couponId}/issues`, null, {
    headers: { Cookie: login.cookies.JSESSIONID ? `JSESSIONID=${login.cookies.JSESSIONID[0].value}` : '' },
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

  check(issue, {
    'issue terminal status': () => issue.status === 200 || issueCode === 'SOLD_OUT',
    'no duplicate issue during load': () => issueCode !== 'ALREADY_ISSUED',
  });

  sleep(0.1);
}
