import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const transferSuccessRate = new Rate('transfer_success_rate');
const transferDuration = new Trend('transfer_duration_ms');
const completedTransfers = new Counter('completed_transfers_total');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  stages: [
    { duration: '5s', target: 20 },   // Warm-up ramp
    { duration: '20s', target: 50 },  // Concurrency benchmark at 50 VUs
    { duration: '5s', target: 0 },    // Cool-down ramp
  ],
  thresholds: {
    'http_req_duration': ['p(95)<500', 'p(99)<1000'],
    'transfer_success_rate': ['rate>0.99'],
    'http_req_failed': ['rate<0.01'],
  },
};

// Helper: UUID v4 generator
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Setup: Provision users and fund initial balance
export function setup() {
  const timestamp = Date.now();
  const userAEmail = `k6_userA_${timestamp}@fintech.com`;
  const userBEmail = `k6_userB_${timestamp}@fintech.com`;
  const password = 'Password@123';

  // 1. Register User A
  const regARes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({ fullName: 'Load User A', email: userAEmail, password: password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(regARes, { 'User A registration successful': (r) => r.status === 200 || r.status === 201 });
  const userAData = regARes.json();
  const tokenA = userAData.token;
  const walletAId = userAData.wallet.id;

  // 2. Fund User A with 10,000,000 paise (₹100,000.00)
  const depositRes = http.post(
    `${BASE_URL}/api/v1/wallets/deposit`,
    JSON.stringify({ amount: 10000000, description: 'k6 initial funding' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${tokenA}`,
        'Idempotency-Key': `k6-dep-${uuidv4()}`,
      },
    }
  );
  check(depositRes, { 'User A deposit funded': (r) => r.status === 200 });

  // 3. Register User B
  const regBRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({ fullName: 'Load User B', email: userBEmail, password: password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(regBRes, { 'User B registration successful': (r) => r.status === 200 || r.status === 201 });
  const userBData = regBRes.json();
  const tokenB = userBData.token;
  const walletBId = userBData.wallet.id;

  console.log(`[k6 Setup] User A Wallet: ${walletAId}, User B Wallet: ${walletBId}`);

  return {
    tokenA,
    walletAId,
    tokenB,
    destinationWalletId: walletBId,
    initialTotalFunds: 10000000,
  };
}

// Benchmark iteration: Concurrent transfers
export default function (data) {
  const idempotencyKey = uuidv4();
  const transferAmount = 100; // 100 paise (₹1.00)

  const payload = JSON.stringify({
    destinationWalletId: data.destinationWalletId,
    amount: transferAmount,
    description: 'k6 high-concurrency transfer benchmark',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${data.tokenA}`,
      'Idempotency-Key': idempotencyKey,
    },
  };

  const startTime = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/wallets/transfer`, payload, params);
  const latency = Date.now() - startTime;

  transferDuration.add(latency);

  const isSuccess = check(res, {
    'status is 200 OK': (r) => r.status === 200,
    'has valid transactionId': (r) => {
      try {
        return r.json().transactionId !== undefined;
      } catch (e) {
        return false;
      }
    },
    'transaction status is COMPLETED': (r) => {
      try {
        return r.json().status === 'COMPLETED';
      } catch (e) {
        return false;
      }
    },
  });

  transferSuccessRate.add(isSuccess);
  if (isSuccess) {
    completedTransfers.add(1);
  }

  // Brief sleep to simulate realistic burst throughput
  sleep(0.01);
}

// Teardown: Verify balance conservation across the double-entry ledger
export function teardown(data) {
  const resA = http.get(`${BASE_URL}/api/v1/wallets/me`, {
    headers: { 'Authorization': `Bearer ${data.tokenA}` },
  });
  const resB = http.get(`${BASE_URL}/api/v1/wallets/me`, {
    headers: { 'Authorization': `Bearer ${data.tokenB}` },
  });

  const walletA = resA.json();
  const walletB = resB.json();

  const totalBalance = walletA.balance + walletB.balance;
  console.log('====================================================');
  console.log(`[k6 Teardown] User A Final Balance: ${walletA.balance} paise`);
  console.log(`[k6 Teardown] User B Final Balance: ${walletB.balance} paise`);
  console.log(`[k6 Teardown] Total System Balance:  ${totalBalance} paise`);
  console.log(`[k6 Teardown] Invariant Holds:      ${totalBalance === data.initialTotalFunds}`);
  console.log('====================================================');

  check(totalBalance, {
    'Conservation of funds strictly conserved': (tot) => tot === data.initialTotalFunds,
  });
}
