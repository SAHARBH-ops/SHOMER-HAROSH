const FREE_MONTHLY_SCAN_LIMIT = 5;
const PRO_MONTHLY_SCAN_LIMIT = 40;
const MONTHLY_QUOTA_TTL_SECONDS = 3024000; // 35 days
const MIN_IMAGE_BASE64_LEN = 1000;
const MAX_IMAGE_BASE64_LEN = 8000000; // ~6MB

const CORS_HEADERS = {
  'Content-Type': 'application/json; charset=utf-8',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Phone-Number, X-Device-ID, X-App-Token'
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: CORS_HEADERS });
}

function buildResponse(payload, status = 200) {
  const enriched = {
    ...payload,
    title: payload.alert_title || '',
    message: payload.simple_explanation || '',
    text: `${payload.alert_title || ''}\n${payload.headline ? payload.headline + '\n' : ''}${payload.simple_explanation || ''}`.trim()
  };
  return jsonResponse(enriched, status);
}

const FALLBACK_ALERT = {
  alert_title: '⚠️ זהירות! לא ניתן לאמת את התוכן',
  headline: 'מומלץ לנהוג במשנה זהירות',
  simple_explanation: 'המערכת לא הצליחה לנתח את התמונה במלואה. אל תשתף פרטים אישיים, קודים או סיסמאות.'
};

function normalizePhone(raw) {
  let digits = String(raw || '').trim().replace(/\D/g, '');
  if (digits.length === 12 && digits.startsWith('972')) {
    digits = '0' + digits.slice(3);
  }
  return digits;
}

function getCurrentMonthKey() {
  return new Date().toISOString().slice(0, 7); // YYYY-MM
}

function parseUsageCount(raw) {
  if (!raw) return 0;
  const trimmed = String(raw).trim();
  if (/^\d+$/.test(trimmed)) return parseInt(trimmed, 10);
  try {
    const parsed = JSON.parse(trimmed);
    const n = Number(parsed?.count ?? parsed?.used ?? 0);
    return Number.isFinite(n) && n >= 0 ? n : 0;
  } catch {
    return 0;
  }
}

function isProSubscriptionActive(data) {
  return Boolean(
    data &&
    data.plan === 'PRO' &&
    (!data.expiresAt || Date.parse(data.expiresAt) > Date.now())
  );
}

async function evaluatePhoneQuota(env, normalizedPhone) {
  const month = getCurrentMonthKey();
  let subRecord = null;
  let isActivePro = false;

  if (env.SCAN_QUOTAS && normalizedPhone) {
    const rawSub = await env.SCAN_QUOTAS.get(`phone:${normalizedPhone}`);
    if (rawSub) {
      try {
        subRecord = JSON.parse(rawSub);
        isActivePro = isProSubscriptionActive(subRecord);
      } catch {}
    }
  }

  const plan = isActivePro ? 'PRO' : 'FREE';
  const limit = isActivePro ? PRO_MONTHLY_SCAN_LIMIT : FREE_MONTHLY_SCAN_LIMIT;
  const usageKey = `used:${normalizedPhone}:${month}`;
  let used = 0;

  if (env.SCAN_QUOTAS && normalizedPhone) {
    const rawUsed = await env.SCAN_QUOTAS.get(usageKey);
    used = parseUsageCount(rawUsed);
  }

  const remaining = Math.max(0, limit - used);
  return { phone: normalizedPhone, plan, limit, used, remaining, month, usageKey, subRecord };
}

function pickField(payload, candidates) {
  const sources = [payload];
  for (const nestedKey of ['data', 'customer', 'payer', 'transaction']) {
    if (payload && typeof payload[nestedKey] === 'object' && payload[nestedKey] !== null) {
      sources.push(payload[nestedKey]);
    }
  }
  const lowerCandidates = candidates.map(c => c.toLowerCase());
  for (const src of sources) {
    for (const key of Object.keys(src)) {
      if (lowerCandidates.includes(key.toLowerCase())) {
        const val = src[key];
        if (val !== undefined && val !== null && String(val).trim() !== '') {
          return val;
        }
      }
    }
  }
  return undefined;
}

async function parseWebhookPayload(request, url) {
  const merged = Object.fromEntries(url.searchParams.entries());
  const contentType = (request.headers.get('content-type') || '').toLowerCase();

  if (contentType.includes('application/json')) {
    const bodyText = await request.text();
    if (!bodyText.trim()) return merged;
    const parsed = JSON.parse(bodyText);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('JSON payload must be an object');
    }
    return { ...merged, ...parsed };
  }

  if (contentType.includes('application/x-www-form-urlencoded')) {
    const bodyText = await request.text();
    if (!bodyText.trim()) return merged;
    return { ...merged, ...Object.fromEntries(new URLSearchParams(bodyText).entries()) };
  }

  if (contentType.includes('multipart/form-data')) {
    const formData = await request.formData();
    const formObj = {};
    for (const [key, value] of formData.entries()) {
      formObj[key] = typeof value === 'string' ? value : value.name;
    }
    return { ...merged, ...formObj };
  }

  const fallbackText = await request.text();
  if (fallbackText.trim()) {
    try {
      const parsed = JSON.parse(fallbackText);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return { ...merged, ...parsed };
      }
    } catch {
      if (fallbackText.includes('=')) {
        return { ...merged, ...Object.fromEntries(new URLSearchParams(fallbackText).entries()) };
      }
      throw new Error('Unsupported payload format');
    }
  }

  return merged;
}

function isPaymentSuccessful(payload) {
  for (const flagKey of ['success', 'paid', 'approved', 'is_paid', 'isPaid']) {
    const val = pickField(payload, [flagKey]);
    if (val === false || String(val).trim().toLowerCase() === 'false' || String(val).trim() === '0') {
      return false;
    }
  }

  const errCode = pickField(payload, ['error_code', 'errorCode', 'returncode', 'return_code', 'response_code']);
  if (errCode !== undefined) {
    const codeStr = String(errCode).trim().toLowerCase();
    if (!['0', '00', '000', 'ok', 'none', 'null'].includes(codeStr)) {
      return false;
    }
  }

  const errMsg = pickField(payload, ['errormessage', 'error_message', 'error']);
  if (errMsg !== undefined) {
    const msgStr = String(errMsg).trim().toLowerCase();
    if (!['0', 'ok', 'none', 'null', 'success', 'ללא שגיאה'].includes(msgStr)) {
      return false;
    }
  }

  const rawStatus = pickField(payload, ['status', 'payment_status', 'paymentStatus', 'transaction_status', 'transactionStatus', 'state', 'result']);
  if (rawStatus !== undefined) {
    const statusStr = String(rawStatus).trim().toLowerCase();
    const accepted = ['success', 'succeeded', 'paid', 'approved', 'complete', 'completed', 'ok', 'valid', 'true', '1', 'yes', 'active', 'confirmed', '000', '00', 'הצלחה', 'שולם', 'אושר', 'תקין'];
    const rejected = ['failed', 'fail', 'failure', 'cancel', 'cancelled', 'canceled', 'declined', 'rejected', 'error', 'unpaid', 'pending', 'void', 'refunded', 'chargeback', 'false', '0', 'no', 'בוטל', 'נכשל'];
    if (rejected.includes(statusStr)) {
      return false;
    }
    // Unknown status strings are allowed through; only explicit rejection values block payment
  }

  return true;
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS_HEADERS });
    const url = new URL(request.url);

    // 1. UPay payment webhook endpoint
    if (request.method === 'POST' && url.pathname === '/upay-webhook') {
      let payload;
      try {
        payload = await parseWebhookPayload(request, url);
      } catch (err) {
        return jsonResponse({ success: false, error: err.message || 'Invalid webhook payload' }, 400);
      }

      console.log('UPAY_WEBHOOK_PAYLOAD', JSON.stringify(payload));

      if (!isPaymentSuccessful(payload)) {
        return jsonResponse({ success: false, error: 'Payment not completed or failed/cancelled' }, 400);
      }

      const rawPhone = pickField(payload, [
        'phone', 'mobile', 'cell', 'payer_phone', 'customer_phone',
        'phone_number', 'phoneNumber', 'payerPhone', 'customerPhone',
        'client_phone', 'clientPhone', 'cellphone', 'telephone', 'tel'
      ]);
      const normalizedPhone = normalizePhone(rawPhone);
      if (!normalizedPhone || normalizedPhone.length < 9) {
        return jsonResponse({ success: false, error: 'Missing or invalid payer phone number' }, 400);
      }

      const rawAmount = pickField(payload, ['amount', 'sum', 'total', 'payment_amount', 'paymentAmount', 'sum_paid', 'sumPaid', 'price']);
      let amount = 0;
      if (rawAmount !== undefined) {
        amount = parseFloat(String(rawAmount).replace(/[^0-9.-]/g, ''));
        if (!Number.isFinite(amount) || amount < 0) {
          return jsonResponse({ success: false, error: 'Invalid transaction amount' }, 400);
        }
      }
      // If amount is 0 (not provided or explicitly 0), default to monthly tier

      const billingCycle = amount >= 100 ? 'yearly' : 'monthly';
      const ttlDays = billingCycle === 'yearly' ? 365 : 35;
      const nowMs = Date.now();
      const expiresAt = new Date(nowMs + ttlDays * 24 * 60 * 60 * 1000).toISOString();
      const updatedAt = new Date(nowMs).toISOString();

      if (!env.SCAN_QUOTAS) {
        return jsonResponse({ success: false, error: 'SCAN_QUOTAS KV binding is not configured' }, 500);
      }

      const kvKey = `phone:${normalizedPhone}`;
      let existingBoundDeviceId = null;
      try {
        const existingRaw = await env.SCAN_QUOTAS.get(kvKey);
        if (existingRaw) {
          const existingData = JSON.parse(existingRaw);
          if (existingData?.bound_device_id) {
            existingBoundDeviceId = existingData.bound_device_id;
          }
        }
      } catch {}

      const subscriptionRecord = {
        plan: 'PRO',
        billingCycle,
        monthlyLimit: PRO_MONTHLY_SCAN_LIMIT,
        expiresAt,
        updatedAt
      };
      if (existingBoundDeviceId) {
        subscriptionRecord.bound_device_id = existingBoundDeviceId;
      }

      await env.SCAN_QUOTAS.put(kvKey, JSON.stringify(subscriptionRecord));

      return jsonResponse({
        success: true,
        phone: normalizedPhone,
        ...subscriptionRecord
      }, 200);
    }

    // 2. Quota check endpoint (GET /quota or POST /quota)
    if ((request.method === 'GET' || request.method === 'POST') && url.pathname === '/quota') {
      let rawPhone = url.searchParams.get('phone') || url.searchParams.get('phone_number') || request.headers.get('X-Phone-Number');
      if (!rawPhone && request.method === 'POST') {
        try {
          const body = await request.json();
          rawPhone = body.phone || body.phone_number;
        } catch {}
      }
      const normalizedPhone = normalizePhone(rawPhone);
      if (!normalizedPhone || normalizedPhone.length < 9) {
        return jsonResponse({ error: 'Valid phone number is required' }, 400);
      }
      const quota = await evaluatePhoneQuota(env, normalizedPhone);
      return jsonResponse({
        phone: quota.phone,
        plan: quota.plan,
        limit: quota.limit,
        used: quota.used,
        remaining: quota.remaining
      }, 200);
    }

    // 3. Webhook מאובטח להפעלת מנוי PRO אוטומטית לאחר תשלום (legacy/admin)
    if (request.method === 'POST' && url.pathname === '/webhook/payment') {
      try {
        const data = await request.json();
        const authHeader = request.headers.get('Authorization') || '';
        const providedSecret = authHeader.replace(/^Bearer\s+/i, '').trim() || String(data.secret || '').trim();

        if (!env.WEBHOOK_SECRET || providedSecret !== env.WEBHOOK_SECRET) {
          return buildResponse({ success: false, error: 'Unauthorized webhook request' }, 401);
        }

        const phoneNumber = normalizePhone(data.phone_number || data.phone);
        const targetDeviceId = String(data.device_id || '').trim();
        const billingCycle = (data.plan === 'annual' || data.plan === 'yearly') ? 'yearly' : 'monthly';
        const ttlDays = billingCycle === 'yearly' ? 365 : 35;
        const nowMs = Date.now();

        if ((!phoneNumber || phoneNumber.length < 9) && targetDeviceId.length < 6) {
          return buildResponse({ success: false, error: 'נדרש מספר טלפון או מזהה מכשיר תקין' }, 400);
        }

        const subscriptionData = {
          plan: 'PRO',
          billingCycle,
          monthlyLimit: PRO_MONTHLY_SCAN_LIMIT,
          expiresAt: new Date(nowMs + ttlDays * 24 * 60 * 60 * 1000).toISOString(),
          updatedAt: new Date(nowMs).toISOString(),
          bound_device_id: targetDeviceId || null
        };

        if (env.SCAN_QUOTAS) {
          if (phoneNumber.length >= 9) {
            await env.SCAN_QUOTAS.put(`phone:${phoneNumber}`, JSON.stringify(subscriptionData));
            await env.SCAN_QUOTAS.put(`user_profile:${phoneNumber}`, JSON.stringify({
              phone: phoneNumber,
              device_id: targetDeviceId || 'unknown',
              tier: 'PRO',
              last_seen: new Date(nowMs).toISOString()
            }));
          }
          if (targetDeviceId.length >= 6) {
            await env.SCAN_QUOTAS.put(`pro_device:${targetDeviceId}`, JSON.stringify(subscriptionData));
          }
        }

        return buildResponse({ success: true, message: 'המנוי הופעל בהצלחה' });
      } catch (err) {
        return buildResponse({ success: false, error: err.message }, 400);
      }
    }

    if (request.method !== 'POST') {
      return buildResponse({ alert_title: 'שגיאה', simple_explanation: 'Method not allowed' }, 405);
    }

    let body = {};
    try { body = await request.json(); } catch { return buildResponse(FALLBACK_ALERT, 400); }

    const rawImage = String(body.image_base64 || body.image || '');
    const imageBase64 = rawImage
      .replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/i, '')
      .replace(/\s+/g, '');

    if (!imageBase64 || imageBase64.length < MIN_IMAGE_BASE64_LEN) {
      return buildResponse({ alert_title: '⚠️ חסרה תמונה תקינה', simple_explanation: 'לא התקבל צילום מסך תקין לבדיקה.' }, 400);
    }
    if (imageBase64.length > MAX_IMAGE_BASE64_LEN) {
      return buildResponse({ alert_title: '⚠️ התמונה גדולה מדי', simple_explanation: 'צילום המסך כבד מדי לעיבוד. נסה לצלם שוב.' }, 413);
    }

    const suppliedPhone = normalizePhone(body.phone_number || body.phone || request.headers.get('X-Phone-Number'));
    const deviceId = String(body.device_id || request.headers.get('X-Device-ID') || '').trim();
    const clientIP = request.headers.get('CF-Connecting-IP') || 'unknown';
    const hasValidDeviceId = deviceId.length >= 6 && deviceId !== 'unknown';

    // קריאת רשימות ה-VIP מתוך משתני הסביבה של Cloudflare
    const vipIds = String(env.VIP_IDS || '')
      .split(',')
      .map(s => s.trim())
      .filter(Boolean);
    const vipPhones = String(env.VIP_PHONES || '')
      .split(',')
      .map(s => normalizePhone(s))
      .filter(Boolean);

    let isPro = false;
    let isVip = false;
    let maxAllowedScans = FREE_MONTHLY_SCAN_LIMIT;
    const quotaTtl = MONTHLY_QUOTA_TTL_SECONDS;
    let phoneQuota = null;

    // 1. בדיקת VIP מיידית (לפי מזהה מכשיר או מספר טלפון)
    if ((hasValidDeviceId && vipIds.includes(deviceId)) || (suppliedPhone && vipPhones.includes(suppliedPhone))) {
      isVip = true;
      isPro = true;
      maxAllowedScans = 99999;
    }

    // בדיקת VIP ב-KV (רק VIP עוקף מגבלת סריקות; PRO נבדק מול תוקף ומכסה)
    if (!isVip && env.SCAN_QUOTAS && suppliedPhone) {
      const directKvProf = await env.SCAN_QUOTAS.get(`user_profile:${suppliedPhone}`);
      if (directKvProf) {
        try {
          const pData = JSON.parse(directKvProf);
          if (pData.tier === 'VIP') {
            isVip = true;
            isPro = true;
            maxAllowedScans = 99999;
          }
        } catch {}
      }
    }

    // 2. בדיקת מנוי PRO לפי מספר טלפון + תוקף + נעילה למכשיר אחד + מכסה חודשית (40 PRO / 5 FREE)
    if (!isVip && suppliedPhone.length >= 9 && env.SCAN_QUOTAS) {
      phoneQuota = await evaluatePhoneQuota(env, suppliedPhone);
      if (phoneQuota.plan === 'PRO') {
        const subRecord = phoneQuota.subRecord || {};
        if (!subRecord.bound_device_id && hasValidDeviceId) {
          subRecord.bound_device_id = deviceId;
          await env.SCAN_QUOTAS.put(`phone:${suppliedPhone}`, JSON.stringify(subRecord));
        }

        if (subRecord.bound_device_id && hasValidDeviceId && subRecord.bound_device_id !== deviceId) {
          return buildResponse({
            alert_title: '❌ מנוי בשימוש במכשיר אחר',
            headline: 'זוהה ניסיון שימוש במספר ממכשיר שאינו מורשה',
            simple_explanation: 'מספר טלפון זה כבר משויך למכשיר אחר. כל מנוי מוגבל למכשיר אחד בלבד.'
          }, 429);
        }

        isPro = true;
        maxAllowedScans = phoneQuota.limit;

        if (phoneQuota.remaining <= 0) {
          return buildResponse({
            alert_title: '⛔ הגעת למכסת 40 הבדיקות החודשית',
            headline: 'מכסת מסלול PRO לחודש זה הסתיימה',
            simple_explanation: 'ניצלת את כל 40 הבדיקות החודשיות במנוי PRO. המכסה תתחדש בתחילת החודש הבא.'
          }, 429);
        }
      }
    }

    // 3. בדיקת מנוי PRO לפי מזהה מכשיר ב-KV (גיבוי כשאין טלפון או לפני שיוך טלפון)
    if (!isVip && !isPro && hasValidDeviceId && env.SCAN_QUOTAS) {
      const rawDevSub = await env.SCAN_QUOTAS.get(`pro_device:${deviceId}`);
      if (rawDevSub) {
        try {
          const devSub = JSON.parse(rawDevSub);
          if (isProSubscriptionActive(devSub)) {
            isPro = true;
            maxAllowedScans = PRO_MONTHLY_SCAN_LIMIT;
          }
        } catch {}
      }
    }

    // 4. אכיפת מכסת חינם (5 בחודש לפי טלפון, מכשיר ו-IP למניעת עקיפה)
    const freeDevKey = hasValidDeviceId ? `free_dev:${deviceId}:monthly` : '';
    const freeIpKey = clientIP !== 'unknown' ? `free_ip:${clientIP}:monthly` : '';
    let devScanCount = 0;
    let ipScanCount = 0;

    if (!isVip && !isPro && env.SCAN_QUOTAS) {
      if (freeDevKey) {
        try { devScanCount = JSON.parse(await env.SCAN_QUOTAS.get(freeDevKey) || '{}').count || 0; } catch {}
      }
      if (freeIpKey) {
        try { ipScanCount = JSON.parse(await env.SCAN_QUOTAS.get(freeIpKey) || '{}').count || 0; } catch {}
      }

      const phoneFreeExceeded = phoneQuota && phoneQuota.remaining <= 0;
      if (phoneFreeExceeded || devScanCount >= FREE_MONTHLY_SCAN_LIMIT || ipScanCount >= FREE_MONTHLY_SCAN_LIMIT * 2) {
        return buildResponse({
          alert_title: `⛔ הגעת למכסת ${FREE_MONTHLY_SCAN_LIMIT} הבדיקות החינמיות`,
          headline: 'נגמרו הבדיקות החינמיות לחודש',
          simple_explanation: `הגעת למכסת ${FREE_MONTHLY_SCAN_LIMIT} הבדיקות החינמיות החודשית. לשדרוג למנוי PRO (עד 40 בדיקות בחודש) היכנס לאתר.`
        }, 429);
      }
    }

    // רישום או עדכון פרופיל המשתמש ב-KV (רק VIP נשמר קבוע; PRO משקף תוקף נוכחי)
    if (env.SCAN_QUOTAS && (suppliedPhone.length >= 9 || hasValidDeviceId)) {
      const profileKey = suppliedPhone.length >= 9 ? `user_profile:${suppliedPhone}` : `user_profile:dev_${deviceId}`;
      let finalTier = isVip ? 'VIP' : isPro ? 'PRO' : 'FREE';

      try {
        const oldProfRaw = await env.SCAN_QUOTAS.get(profileKey);
        if (oldProfRaw) {
          const oldProf = JSON.parse(oldProfRaw);
          if (oldProf.tier === 'VIP') {
            finalTier = 'VIP';
          }
        }
      } catch {}

      try {
        await env.SCAN_QUOTAS.put(profileKey, JSON.stringify({
          phone: suppliedPhone || 'לא הוזן',
          device_id: deviceId || 'unknown',
          tier: finalTier,
          last_seen: new Date().toISOString()
        }));
      } catch {}
    }

    const apiKey = env.GEMINI_API_KEY;
    if (!apiKey) return buildResponse({ alert_title: '⚠️ שגיאת שרת', simple_explanation: 'חסר מפתח API בשרת.' }, 500);

    let mimeType = 'image/jpeg';
    if (imageBase64.startsWith('iVBOR')) mimeType = 'image/png';
    else if (imageBase64.startsWith('UklGR')) mimeType = 'image/webp';

    // פרומפט מכויל: מתמקד בהודעה האחרונה, נותן סיבה קונקרטית ב-WARNING, ושומר על ניסוח זהיר
    const promptText = `Analyze this Israeli mobile screenshot for phishing, fraud, or spam indicators objectively.
Focus primarily on the LATEST (bottom-most) message or active popup on the screen, ignoring older chat history above unless relevant.

Classification rules:
- DANGER: Clear impersonation of banks, Bituach Leumi, Israel Post/customs demanding urgent payment, fake loans, or requests for passwords, OTP codes, credit card details, or ID photos.
- WARNING: Unknown sender with a shortened/unfamiliar link (e.g., bit.ly, did.li), exaggerated job/loan/prize offer, urgent pressure from an unverified number, or sensitive personal document.
- SAFE: Normal personal conversation, official delivery notification without a payment link, or routine marketing/coupons from recognized Israeli retail/food brands that do not ask for credentials or payment details.

Writing rules (Hebrew only):
- Never accuse a real company or brand definitively; describe the specific risk indicators neutrally.
- In "simple_explanation", ALWAYS state the concrete reason for the classification in 1-2 short, clear sentences (e.g., "ההודעה מכילה קישור מקוצר לא מוכר והצעת שכר חריגה ללא שם חברה ברור." or "זו הודעת תזכורת שגרתית ללא בקשת פרטים או תשלום."). Never write generic filler like "הסריקה הושלמה".`;

    const callModel = async (model, timeoutMs) => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);

      const payload = {
        contents: [{
          parts: [
            { text: promptText },
            { inlineData: { mimeType, data: imageBase64 } }
          ]
        }],
        safetySettings: [
          { category: 'HARM_CATEGORY_HARASSMENT', threshold: 'BLOCK_NONE' },
          { category: 'HARM_CATEGORY_HATE_SPEECH', threshold: 'BLOCK_NONE' },
          { category: 'HARM_CATEGORY_SEXUALLY_EXPLICIT', threshold: 'BLOCK_NONE' },
          { category: 'HARM_CATEGORY_DANGEROUS_CONTENT', threshold: 'BLOCK_NONE' }
        ],
        generationConfig: {
          temperature: 0.1,
          responseMimeType: 'application/json',
          responseSchema: {
            type: 'OBJECT',
            properties: {
              risk_level: {
                type: 'STRING',
                enum: ['DANGER', 'WARNING', 'SAFE']
              },
              headline: {
                type: 'STRING',
                description: '2 to 4 words in Hebrew summarizing the finding'
              },
              simple_explanation: {
                type: 'STRING',
                description: '1 to 2 short sentences in Hebrew explaining the specific reason'
              }
            },
            required: ['risk_level', 'headline', 'simple_explanation']
          },
          maxOutputTokens: 1024
        }
      };

      try {
        const res = await fetch(
          `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
          { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload), signal: controller.signal }
        );
        const rawBody = await res.text();
        if (!res.ok) {
          console.error(`GEMINI_ERR [${model}] ${res.status}:`, rawBody.slice(0, 300));
          return { text: '', blocked: false };
        }
        const parsedApi = JSON.parse(rawBody);
        const finishReason = parsedApi?.candidates?.[0]?.finishReason || '';
        const blockReason = parsedApi?.promptFeedback?.blockReason || '';
        if (blockReason || finishReason === 'SAFETY' || finishReason === 'PROHIBITED_CONTENT') {
          console.warn(`GEMINI_BLOCKED [${model}]:`, blockReason || finishReason);
          return { text: '', blocked: true };
        }
        const text = parsedApi?.candidates?.[0]?.content?.parts?.map(p => p?.text || '').join('') || '';
        return { text, blocked: false };
      } catch (err) {
        console.error(`GEMINI_CATCH [${model}]:`, err.message);
        return { text: '', blocked: false };
      } finally {
        clearTimeout(timer);
      }
    };

    try {
      let result = await callModel('gemini-3.8-flash', 9000);
      if (!result.text && !result.blocked) {
        result = await callModel('gemini-flash-latest', 9000);
      }

      if (result.blocked) {
        return buildResponse({
          alert_title: '🚨 זהירות! מסמך או מידע רגיש',
          headline: 'זוהו פרטים אישיים או פיננסיים בתמונה',
          simple_explanation: 'הצילום מכיל מסמך מזהה או פרטי חשבון רגישים. לעולם אל תשלח צילומים כאלו לגורם לא מוכר.'
        }, 200);
      }

      if (!result.text) {
        return buildResponse(FALLBACK_ALERT, 200);
      }

      let parsed = {};
      try {
        const jsonMatch = result.text.match(/\{[\s\S]*\}/);
        parsed = JSON.parse(jsonMatch ? jsonMatch[0] : result.text);
      } catch {
        return buildResponse(FALLBACK_ALERT, 200);
      }

      const risk = String(parsed.risk_level || 'WARNING').toUpperCase();
      const validRisk = ['DANGER', 'WARNING', 'SAFE'].includes(risk) ? risk : 'WARNING';

      const alertTitle = validRisk === 'SAFE'
        ? '✅ לא זוהו סימני הונאה מוכרים'
        : validRisk === 'DANGER'
          ? '🚨 אזהרת אבטחה: זוהו מאפייני הונאה'
          : '⚠️ התראת זהירות: מומלץ לבדוק';

      const headline = String(parsed.headline || (validRisk === 'SAFE' ? 'נראה תקין' : 'נדרשת זהירות'))
        .replace(/^[\\/"'\s]+|[\\/"'\s]+$/g, '')
        .slice(0, 80);

      const baseExplanation = String(parsed.simple_explanation || 'זוהו מאפיינים שדורשים בדיקה לפני לחיצה או מסירת פרטים.')
        .replace(/^[\\/"'\s]+|[\\/"'\s]+$/g, '')
        .slice(0, 240);

      const explanation = validRisk === 'SAFE'
        ? `${baseExplanation} (הערכה אוטומטית בלבד — תמיד הפעל שיקול דעת).`
        : baseExplanation;

      // עדכון מונה השימוש החודשי לפי טלפון (PRO ו-FREE) ומוני החינם (Device + IP)
      if (!isVip && env.SCAN_QUOTAS) {
        if (phoneQuota?.usageKey) {
          try {
            await env.SCAN_QUOTAS.put(phoneQuota.usageKey, String(phoneQuota.used + 1), { expirationTtl: quotaTtl });
          } catch {}
        }
        if (!isPro) {
          if (freeDevKey) {
            try { await env.SCAN_QUOTAS.put(freeDevKey, JSON.stringify({ count: devScanCount + 1 }), { expirationTtl: quotaTtl }); } catch {}
          }
          if (freeIpKey) {
            try { await env.SCAN_QUOTAS.put(freeIpKey, JSON.stringify({ count: ipScanCount + 1 }), { expirationTtl: quotaTtl }); } catch {}
          }
        }
      }

      return buildResponse({ alert_title: alertTitle, headline, simple_explanation: explanation }, 200);
    } catch {
      return buildResponse(FALLBACK_ALERT, 200);
    }
  }
};