import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: Number(__ENV.VUS || 100),
  iterations: Number(__ENV.ITERATIONS || 1000),
  thresholds: {
    http_req_failed: ['rate<0.20'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID || '1';

export default function () {
  const username = `load-user-${__VU}-${__ITER}`;
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

  check(issue, {
    'issue terminal status': (response) => [200, 409].includes(response.status),
  });

  sleep(0.1);
}
