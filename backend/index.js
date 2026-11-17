const MONTHLY_SCAN_LIMIT = 3;
const MONTHLY_QUOTA_TTL_SECONDS = 2678400;

const CORS_HEADERS = {
  'Content-Type': 'application/json; charset=utf-8',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Phone-Number, X-Device-ID'
};

function buildResponse(payload, status = 200) {
  const enriched = {
    ...payload,
    title: payload.alert_title || '',
    message: payload.simple_explanation || '',
    text: `${payload.alert_title || ''}\n${payload.headline ? payload.headline + '\n' : ''}${payload.simple_explanation || ''}`.trim()
  };
  return new Response(JSON.stringify(enriched), { status, headers: CORS_HEADERS });
}

const FALLBACK_ALERT = {
  alert_title: '⚠️ שגיאת זמינות בסריקה',
  headline: 'תקלה זמנית בניתוח המסך',
  simple_explanation: 'אירעה שגיאה זמנית בניתוח הצילום. נסה שוב או בדוק את חיבור האינטרנט.'
};

function normalizePhone(raw) {
  let digits = String(raw || '').trim().replace(/[^0-9]/g, '');
  if (digits.startsWith('972')) digits = '0' + digits.slice(3);
  return digits;
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS_HEADERS });
    const url = new URL(request.url);

    // Webhook להפעלת מנוי PRO אוטומטית לאחר תשלום
    if (request.method === 'POST' && url.pathname === '/webhook/payment') {
      try {
        const data = await request.json();
        const phoneNumber = normalizePhone(data.phone_number);
        const targetDeviceId = String(data.device_id || '').trim();
        const plan = data.plan || 'monthly';

        if ((!phoneNumber || phoneNumber.length < 9) && targetDeviceId.length < 6) {
          return buildResponse({ success: false, error: 'נדרש מספר טלפון או מזהה מכשיר תקין' }, 400);
        }

        const subscriptionData = {
          plan,
          bound_device_id: targetDeviceId || null,
          created_at: new Date().toISOString()
        };

        if (env.SCAN_QUOTAS) {
          if (phoneNumber.length >= 9) {
            await env.SCAN_QUOTAS.put(`phone:${phoneNumber}`, JSON.stringify(subscriptionData));
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

    if (request.method !== 'POST') return buildResponse({ alert_title: 'שגיאה', simple_explanation: 'Method not allowed' }, 405);

    let body = {};
    try { body = await request.json(); } catch { return buildResponse(FALLBACK_ALERT, 400); }

    const rawImage = String(body.image_base64 || body.image || '');
    const imageBase64 = rawImage
      .replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/i, '')
      .replace(/\s+/g, '');

    const suppliedPhone = normalizePhone(body.phone_number || request.headers.get('X-Phone-Number'));
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
    let subRecord = null;
    let quotaKvKey = '';
    let maxAllowedScans = MONTHLY_SCAN_LIMIT;
    let scanCount = 0;
    let quotaTtl = MONTHLY_QUOTA_TTL_SECONDS;

    // 1. בדיקת VIP (לפי מזהה מכשיר או מספר טלפון)
    if ((hasValidDeviceId && vipIds.includes(deviceId)) || (suppliedPhone && vipPhones.includes(suppliedPhone))) {
      isVip = true;
      isPro = true;
      maxAllowedScans = 99999;
      quotaKvKey = `usage:vip:${suppliedPhone || deviceId}`;
    }
    // 2. בדיקת מנוי PRO לפי מזהה מכשיר ב-KV
    else if (hasValidDeviceId && env.SCAN_QUOTAS) {
      const rawDevSub = await env.SCAN_QUOTAS.get(`pro_device:${deviceId}`);
      if (rawDevSub) {
        try {
          subRecord = JSON.parse(rawDevSub);
          isPro = true;
        } catch {}
      }
    }

    // 3. בדיקת מנוי PRO לפי מספר טלפון + נעילה אוטומטית למכשיר אחד בלבד
    if (!isPro && suppliedPhone && env.SCAN_QUOTAS) {
      const rawSub = await env.SCAN_QUOTAS.get(`phone:${suppliedPhone}`);
      if (rawSub) {
        try {
          subRecord = JSON.parse(rawSub);
          // בהפעלה הראשונה של המנוי — נועל אותו למזהה המכשיר של הלקוח
          if (!subRecord.bound_device_id && hasValidDeviceId) {
            subRecord.bound_device_id = deviceId;
            await env.SCAN_QUOTAS.put(`phone:${suppliedPhone}`, JSON.stringify(subRecord));
          }

          // אם מנסים להשתמש באותו מספר טלפון ממכשיר אחר — חוסם מיד!
          if (subRecord.bound_device_id && hasValidDeviceId && subRecord.bound_device_id !== deviceId) {
            return buildResponse({
              alert_title: '❌ מנוי בשימוש במכשיר אחר',
              headline: 'זוהה ניסיון שימוש במספר ממכשיר שאינו מורשה',
              simple_explanation: 'מספר טלפון זה כבר משויך למכשיר אחר. כל מנוי מוגבל למכשיר אחד בלבד.'
            }, 429);
          }

          isPro = true;
        } catch {}
      }
    }

    // רישום פרופיל משתמש ב-KV כדי שתוכל לראות ב-Cloudflare מי משתמש במערכת
    if (env.SCAN_QUOTAS && (suppliedPhone.length >= 9 || hasValidDeviceId)) {
      const profileKey = suppliedPhone.length >= 9 ? `user_profile:${suppliedPhone}` : `user_profile:dev_${deviceId}`;
      const tier = isVip ? 'VIP' : isPro ? 'PRO' : 'FREE';
      try {
        await env.SCAN_QUOTAS.put(profileKey, JSON.stringify({
          phone: suppliedPhone || 'לא הוזן',
          device_id: deviceId || 'unknown',
          tier,
          last_seen: new Date().toISOString()
        }));
      } catch {}
    }

    const todayStr = new Date().toISOString().split('T')[0];
    if (isVip) {
      maxAllowedScans = 99999;
    } else if (isPro && subRecord) {
      const subOwner = suppliedPhone || deviceId;
      if (subRecord.plan === 'annual') {
        maxAllowedScans = 30;
        quotaKvKey = `usage:pro:${subOwner}:${todayStr}`;
        quotaTtl = 172800;
      } else {
        maxAllowedScans = 40;
        quotaKvKey = `usage:pro:${subOwner}:monthly`;
      }
    } else {
      // בחינמי: המכסה נספרת לפי מזהה המכשיר הפיזי (מונע איפוס מכסה ע"י החלפת מספר טלפון)
      maxAllowedScans = MONTHLY_SCAN_LIMIT;
      quotaKvKey = `free_ip:${hasValidDeviceId ? deviceId : clientIP}:monthly`;
    }

    if (!isVip && env.SCAN_QUOTAS && quotaKvKey) {
      const currentUsageRaw = await env.SCAN_QUOTAS.get(quotaKvKey);
      if (currentUsageRaw) {
        try { scanCount = JSON.parse(currentUsageRaw).count || 0; } catch {}
      }
      if (scanCount >= maxAllowedScans) {
        const isAnnualDaily = subRecord?.plan === 'annual';
        return buildResponse({
          alert_title: isAnnualDaily ? '⏳ הגעת למכסה היומית' : '⛔ הגעת למכסת 3 הבדיקות החינמיות',
          headline: isAnnualDaily ? 'מכסת 30 הבדיקות להיום הסתיימה' : 'נגמרו הבדיקות החינמיות לחודש',
          simple_explanation: isAnnualDaily
            ? 'הגעת למכסת 30 הבדיקות היומית במנוי השנתי. נסה שוב מחר.'
            : 'הגעת למכסת 3 הבדיקות החינמיות. לשדרוג למנוי פנה אלינו.'
        }, 429);
      }
    }

    if (!imageBase64) return buildResponse({ alert_title: '⚠️ חסרה תמונה', simple_explanation: 'לא התקבל צילום לבדיקה.' }, 400);
    const apiKey = env.GEMINI_API_KEY;
    if (!apiKey) return buildResponse({ alert_title: '⚠️ שגיאת שרת', simple_explanation: 'חסר מפתח API בשרת.' }, 500);

    let mimeType = 'image/jpeg';
    if (imageBase64.startsWith('iVBOR')) mimeType = 'image/png';
    else if (imageBase64.startsWith('UklGR')) mimeType = 'image/webp';

    const promptText = `Analyze this Israeli screenshot for scams/phishing.
DANGER: fake loans, fake Israel Post/customs, bank/Bituach Leumi impersonation, password/OTP/card requests.
WARNING: unknown sender + shortened link or suspicious prize.
SAFE: normal chat, official receipt/delivery, known brand marketing.
Return JSON only: {"risk_level":"DANGER"|"WARNING"|"SAFE","headline":"2-4 words Hebrew","simple_explanation":"1 short sentence Hebrew"}`;

    const callModel = async (model) => {
      const cleanModel = model.replace(/^models\//, '');
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 18000);

      const payload = {
        contents: [{
          parts: [
            { text: promptText },
            { inlineData: { mimeType, data: imageBase64 } }
          ]
        }],
        generationConfig: {
          temperature: 0,
          responseMimeType: 'application/json',
          maxOutputTokens: 600
        }
      };

      try {
        const res = await fetch(
          `https://generativelanguage.googleapis.com/v1beta/models/${cleanModel}:generateContent?key=${apiKey}`,
          { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload), signal: controller.signal }
        );
        const text = await res.text();
        return { res, text, model: cleanModel };
      } catch (err) {
        return { error: err, model: cleanModel };
      } finally {
        clearTimeout(timer);
      }
    };

    try {
      let rawText = '';
      let lastDebugError = '';

      const primaryOut = await callModel('gemini-flash-latest');
      if (primaryOut.res?.ok) {
        try {
          const parsedApi = JSON.parse(primaryOut.text);
          rawText = parsedApi?.candidates?.[0]?.content?.parts?.map(p => p?.text || '').join('') || '';
        } catch (e) {
          lastDebugError = `JSON parse: ${e.message}`;
        }
      } else {
        lastDebugError = primaryOut.error
          ? `Timeout/Net: ${primaryOut.error.message}`
          : `HTTP ${primaryOut.res?.status} (flash-latest): ${primaryOut.text.slice(0, 100)}`;
      }

      if (!rawText) {
        try {
          const listRes = await fetch(`https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`);
          if (listRes.ok) {
            const listData = await listRes.json();
            const dynamicModels = (listData.models || [])
              .filter(m =>
                (m.supportedGenerationMethods || []).includes('generateContent') &&
                /gemini/i.test(m.name) &&
                !/tts|audio|image|embedding|robotics|computer-use|veo|imagen/i.test(m.name)
              )
              .map(m => m.name.replace(/^models\//, ''))
              .filter(name => name !== 'gemini-flash-latest')
              .slice(0, 4);

            for (const dModel of dynamicModels) {
              const out = await callModel(dModel);
              if (out.res?.ok) {
                const parsedApi = JSON.parse(out.text);
                rawText = parsedApi?.candidates?.[0]?.content?.parts?.map(p => p?.text || '').join('') || '';
                if (rawText) break;
              } else {
                lastDebugError += ` | ${dModel}: ${out.res?.status || out.error?.message}`;
              }
            }
          }
        } catch {}
      }

      if (!rawText) {
        return buildResponse({
          alert_title: '⚠️ שגיאת זמינות בסריקה',
          headline: 'תקלה בתקשורת מול מנוע הניתוח',
          simple_explanation: `אירעה שגיאה בניתוח הצילום (${lastDebugError || 'אין תגובה מהמודל'}).`
        }, 200);
      }

      let parsed = {};
      try {
        const jsonMatch = rawText.match(/\{[\s\S]*\}/);
        const sanitized = (jsonMatch ? jsonMatch[0] : rawText).replace(/\\u(?![0-9a-fA-F]{4})/g, '\\\\u');
        parsed = JSON.parse(sanitized);
      } catch {
        const riskMatch = rawText.match(/"risk_level"\s*:\s*"(DANGER|WARNING|SAFE)"/i);
        const headMatch = rawText.match(/"headline"\s*:\s*"([^"]*)"/i);
        const expMatch = rawText.match(/"simple_explanation"\s*:\s*"([^"]*)"/i);
        parsed = {
          risk_level: riskMatch ? riskMatch[1] : 'WARNING',
          headline: headMatch ? headMatch[1] : 'תוצאת סריקה',
          simple_explanation: expMatch ? expMatch[1] : 'הסריקה הושלמה. בדוק בזהירות את פרטי ההודעה.'
        };
      }

      const risk = String(parsed.risk_level || 'WARNING').toUpperCase();
      const validRisk = ['DANGER', 'WARNING', 'SAFE'].includes(risk) ? risk : 'WARNING';

      const alertTitle = validRisk === 'SAFE'
        ? '✅ ההודעה נראית בטוחה'
        : validRisk === 'DANGER'
          ? '🚨 עצור! חשד להונאה / פישינג'
          : '⚠️ זהירות! הודעה חשודה';

      const headline = String(parsed.headline || (validRisk === 'SAFE' ? 'הודעה תקינה' : 'הודעה חשודה'))
        .replace(/^[\\/"'\s]+|[\\/"'\s]+$/g, '')
        .slice(0, 80);
      const explanation = String(parsed.simple_explanation || '')
        .replace(/^[\\/"'\s]+|[\\/"'\s]+$/g, '')
        .slice(0, 240);
        
      if (env.SCAN_QUOTAS && quotaKvKey) {
        try { await env.SCAN_QUOTAS.put(quotaKvKey, JSON.stringify({ count: scanCount + 1 }), { expirationTtl: quotaTtl }); } catch {}
      }

      return buildResponse({ alert_title: alertTitle, headline, simple_explanation: explanation }, 200);
    } catch (err) {
      return buildResponse({
        alert_title: '⚠️ שגיאת זמינות בסריקה',
        headline: 'שגיאה בעיבוד התשובה',
        simple_explanation: `תקלה בפענוח הנתונים: ${err.message}`
      }, 200);
    }
  }
};