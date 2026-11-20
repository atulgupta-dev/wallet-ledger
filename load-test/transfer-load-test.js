import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metric trackers
const transferSuccessRate = new Rate('transfer_success_rate');
const transferDuration = new Trend('transfer_duration_ms');
const completedTransfers = new Counter('completed_transfers_total');
const failedTransfers = new Counter('failed_transfers_total');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POOL_SIZE = 20;
const INITIAL_DEPOSIT_PER_USER = 10000000; // 10,000,000 paise = ₹100,000.00 each
const TRANSFER_AMOUNT = 100; // 100 paise = ₹1.00 per transfer

export const options = {
  stages: [
    { duration: '10s', target: 50 },   // Warm-up ramp to 50 VUs
    { duration: '30s', target: 150 },  // High-throughput peak at 150 VUs
    { duration: '10s', target: 0 },    // Cool-down ramp
  ],
  thresholds: {
    'http_req_duration': ['p(95)<150', 'p(99)<300'],
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

// Setup: Provision and fund a pool of 20 active users
export function setup() {
  const timestamp = Date.now();
  const password = 'Password@123';
  const users = [];

  console.log(`[k6 Setup] Provisioning and funding pool of ${POOL_SIZE} users...`);

  for (let i = 0; i < POOL_SIZE; i++) {
    const email = `k6_pool_${timestamp}_user${i}@fintech.com`;
    const fullName = `Load User ${i}`;

    // 1. Register User
    const regRes = http.post(
      `${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({ fullName, email, password }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    if (regRes.status !== 200 && regRes.status !== 201) {
      console.error(`[k6 Setup Error] Failed to register user ${email}: Status ${regRes.status}, Body: ${regRes.body}`);
      throw new Error(`Registration failed for user ${email}`);
    }

    const regData = regRes.json();
    const token = regData.token;
    // Extract walletId from WalletSummary (supports wallet.walletId and fallback wallet.id)
    const walletId = regData.wallet ? (regData.wallet.walletId || regData.wallet.id) : null;
    const userId = regData.user ? regData.user.id : null;

    if (!walletId) {
      console.error(`[k6 Setup Error] Could not extract walletId for ${email}: ${JSON.stringify(regData)}`);
      throw new Error(`Missing walletId for user ${email}`);
    }

    // 2. Fund User with initial deposit
    const depositKey = `k6-setup-dep-${i}-${uuidv4()}`;
    const depositRes = http.post(
      `${BASE_URL}/api/v1/wallets/deposit`,
      JSON.stringify({
        amount: INITIAL_DEPOSIT_PER_USER,
        description: `k6 initial funding for user ${i}`,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
          'Idempotency-Key': depositKey,
        },
      }
    );

    if (depositRes.status !== 200) {
      console.error(`[k6 Setup Error] Failed to deposit funds for user ${email}: Status ${depositRes.status}, Body: ${depositRes.body}`);
      throw new Error(`Deposit failed for user ${email}`);
    }

    users.push({
      index: i,
      userId: userId,
      email: email,
      token: token,
      walletId: walletId,
    });
  }

  const totalInitialFunds = POOL_SIZE * INITIAL_DEPOSIT_PER_USER;
  console.log(`[k6 Setup Complete] ${POOL_SIZE} users provisioned and funded with ${totalInitialFunds} paise total (₹${totalInitialFunds / 100}.00)`);

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

// Teardown: Verify balance conservation across all 20 wallets
export function teardown(data) {
  console.log('\n====================================================');
  console.log('[k6 Teardown] Verifying System Invariance across all wallets...');
  console.log('====================================================');

  let totalFinalBalance = 0;
  const users = data.users;

  for (let i = 0; i < users.length; i++) {
    const user = users[i];
    const res = http.get(`${BASE_URL}/api/v1/wallets/me`, {
      headers: {
        'Authorization': `Bearer ${user.token}`,
      },
    });

    if (res.status === 200) {
      const wallet = res.json();
      totalFinalBalance += wallet.balance;
      console.log(`  User ${i} [${user.email}]: ${wallet.balance} paise`);
    } else {
      console.error(`  [Teardown Error] Failed to fetch balance for user ${i}: Status ${res.status}`);
    }
  }

  const expectedFunds = data.totalInitialFunds;
  const invariantHolds = totalFinalBalance === expectedFunds;

  console.log('----------------------------------------------------');
  console.log(`Total Initial Funds: ${expectedFunds} paise (₹${expectedFunds / 100}.00)`);
  console.log(`Total Final Balance: ${totalFinalBalance} paise (₹${totalFinalBalance / 100}.00)`);
  console.log(`Conservation Delta:  ${totalFinalBalance - expectedFunds} paise`);
  console.log(`Invariance Verified: ${invariantHolds ? 'PASS (ZERO LEAKAGE)' : 'FAIL (BALANCE MISMATCH)'}`);
  console.log('====================================================\n');

  check(totalFinalBalance, {
    'Double-entry conservation holds strictly across all 20 wallets': (tot) => tot === expectedFunds,
  });
}
