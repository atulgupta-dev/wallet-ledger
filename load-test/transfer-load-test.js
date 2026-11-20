import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metric trackers
const transferSuccessRate = new Rate('transfer_success_rate');
const transferDuration = new Trend('transfer_duration_ms');
const completedTransfers = new Counter('completed_transfers_total');
const failedTransfers = new Counter('failed_transfers_total');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POOL_SIZE = 200;
const INITIAL_DEPOSIT_PER_USER = 10000000; // 10,000,000 paise = ₹100,000.00 each
const TRANSFER_AMOUNT = 100; // 100 paise = ₹1.00 per transfer

export const options = {
  stages: [
    { duration: '10s', target: 40 },  // Warm-up ramp to 40 VUs
    { duration: '30s', target: 80 },  // Peak load benchmark at 80 VUs
    { duration: '10s', target: 0 },   // Cool-down ramp
  ],
  thresholds: {
    'http_req_duration': ['p(95)<120', 'p(99)<250'],
    'transfer_success_rate': ['rate>0.99'],
    'http_req_failed': ['rate<0.01'],
  },
};

// Helper: Fast UUID v4 generator
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Global flag to log first error response immediately
let loggedFirstError = false;

// Setup: Provision and fund a pool of 200 active users using http.batch()
export function setup() {
  const timestamp = Date.now();
  const password = 'Password@123';

  console.log(`[k6 Setup] Batch-provisioning pool of ${POOL_SIZE} users...`);

  // Phase 1: Batch registration
  const regRequests = [];
  const emails = [];
  for (let i = 0; i < POOL_SIZE; i++) {
    const email = `k6_pool_${timestamp}_user${i}@fintech.com`;
    emails.push(email);
    regRequests.push({
      method: 'POST',
      url: `${BASE_URL}/api/v1/auth/register`,
      body: JSON.stringify({ fullName: `Load User ${i}`, email: email, password: password }),
      params: { headers: { 'Content-Type': 'application/json' } },
    });
  }

  const regResponses = http.batch(regRequests);

  const users = [];
  for (let i = 0; i < POOL_SIZE; i++) {
    const res = regResponses[i];
    if (res.status !== 200 && res.status !== 201) {
      console.error(`[k6 Setup Error] Registration failed for ${emails[i]}: Status ${res.status}, Body: ${res.body}`);
      throw new Error(`Registration failed for user ${emails[i]}`);
    }

    const regData = res.json();
    const token = regData.token;
    const walletId = regData.wallet ? (regData.wallet.walletId || regData.wallet.id) : null;
    const userId = regData.user ? regData.user.id : null;

    if (!walletId) {
      console.error(`[k6 Setup Error] Missing walletId for ${emails[i]}: ${JSON.stringify(regData)}`);
      throw new Error(`Missing walletId for user ${emails[i]}`);
    }

    users.push({
      index: i,
      userId: userId,
      email: emails[i],
      token: token,
      walletId: walletId,
    });
  }

  console.log(`[k6 Setup] Batch-funding ${POOL_SIZE} user wallets with ${INITIAL_DEPOSIT_PER_USER} paise each...`);

  // Phase 2: Batch deposit funding
  const depositRequests = [];
  for (let i = 0; i < POOL_SIZE; i++) {
    const user = users[i];
    depositRequests.push({
      method: 'POST',
      url: `${BASE_URL}/api/v1/wallets/deposit`,
      body: JSON.stringify({
        amount: INITIAL_DEPOSIT_PER_USER,
        description: `k6 initial funding for user ${i}`,
      }),
      params: {
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${user.token}`,
          'Idempotency-Key': `k6-setup-dep-${i}-${uuidv4()}`,
        },
      },
    });
  }

  const depositResponses = http.batch(depositRequests);

  for (let i = 0; i < POOL_SIZE; i++) {
    const res = depositResponses[i];
    if (res.status !== 200) {
      console.error(`[k6 Setup Error] Deposit failed for ${users[i].email}: Status ${res.status}, Body: ${res.body}`);
      throw new Error(`Deposit failed for user ${users[i].email}`);
    }
  }

  const totalInitialFunds = POOL_SIZE * INITIAL_DEPOSIT_PER_USER;
  console.log(`[k6 Setup Complete] ${POOL_SIZE} users registered and funded in parallel (${totalInitialFunds} paise total = ₹${totalInitialFunds / 100}.00)`);

  return {
    users: users,
    totalInitialFunds: totalInitialFunds,
  };
}

// Benchmark iteration: Pick random sender and receiver from the pool
export default function (data) {
  const users = data.users;
  const numUsers = users.length;

  // Pick random sender index
  const senderIdx = Math.floor(Math.random() * numUsers);

  // Pick random receiver index distinct from sender (prevents self-transfer)
  let receiverIdx = Math.floor(Math.random() * (numUsers - 1));
  if (receiverIdx >= senderIdx) {
    receiverIdx++;
  }

  const sender = users[senderIdx];
  const receiver = users[receiverIdx];

  const idempotencyKey = uuidv4();

  const payload = JSON.stringify({
    destinationWalletId: receiver.walletId,
    amount: TRANSFER_AMOUNT,
    description: `k6 transfer user${sender.index} -> user${receiver.index}`,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${sender.token}`,
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
        const body = r.json();
        return body && (body.transactionId !== undefined || body.id !== undefined);
      } catch (e) {
        return false;
      }
    },
    'transaction status is COMPLETED': (r) => {
      try {
        const body = r.json();
        return body && body.status === 'COMPLETED';
      } catch (e) {
        return false;
      }
    },
  });

  transferSuccessRate.add(isSuccess);

  if (isSuccess) {
    completedTransfers.add(1);
  } else {
    failedTransfers.add(1);
    // Immediately log details on the first failed request
    if (!loggedFirstError) {
      loggedFirstError = true;
      console.error('====================================================');
      console.error('[k6 FIRST FAILED REQUEST DETECTED]');
      console.error(`URL:     ${res.url}`);
      console.error(`Status:  ${res.status} ${res.status_text}`);
      console.error(`Headers: ${JSON.stringify(res.headers)}`);
      console.error(`Payload: ${payload}`);
      console.error(`Body:    ${res.body}`);
      console.error('====================================================');
    }
  }

  // Minimal sleep (1ms) to maintain high request pacing without thread blocking
  sleep(0.001);
}

// Teardown: Verify balance conservation across all 200 wallets
export function teardown(data) {
  console.log('\n====================================================');
  console.log(`[k6 Teardown] Verifying System Invariance across all ${data.users.length} wallets...`);
  console.log('====================================================');

  const users = data.users;
  const balanceRequests = users.map((user) => ({
    method: 'GET',
    url: `${BASE_URL}/api/v1/wallets/me`,
    params: {
      headers: {
        'Authorization': `Bearer ${user.token}`,
      },
    },
  }));

  const balanceResponses = http.batch(balanceRequests);

  let totalFinalBalance = 0;
  let successfulLookups = 0;

  for (let i = 0; i < users.length; i++) {
    const res = balanceResponses[i];
    if (res.status === 200) {
      const wallet = res.json();
      totalFinalBalance += wallet.balance;
      successfulLookups++;
    } else {
      console.error(`  [Teardown Error] Failed to fetch balance for user ${i}: Status ${res.status}`);
    }
  }

  const expectedFunds = data.totalInitialFunds;
  const conservationDelta = totalFinalBalance - expectedFunds;
  const invariantHolds = totalFinalBalance === expectedFunds && successfulLookups === users.length;

  console.log('----------------------------------------------------');
  console.log(`Verified Wallets:    ${successfulLookups} / ${users.length}`);
  console.log(`Total Initial Funds: ${expectedFunds} paise (₹${expectedFunds / 100}.00)`);
  console.log(`Total Final Balance: ${totalFinalBalance} paise (₹${totalFinalBalance / 100}.00)`);
  console.log(`Conservation Delta:  ${conservationDelta} paise`);
  console.log(`Invariance Verified: ${invariantHolds ? 'PASS (ZERO LEAKAGE)' : 'FAIL (BALANCE MISMATCH)'}`);
  console.log('====================================================\n');

  check(totalFinalBalance, {
    'Double-entry conservation holds strictly across all 200 wallets (Conservation Delta == 0)': (tot) =>
      tot === expectedFunds && conservationDelta === 0,
  });
}
