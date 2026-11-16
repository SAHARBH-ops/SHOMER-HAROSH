let cachedWorkingModelName = null;
const MONTHLY_SCAN_LIMIT = 3;
const MONTHLY_QUOTA_TTL_SECONDS = 2678400;
const FALLBACK_ALERT = {
  alert_title: '⚠️ לא ניתן היה לפענח',
  simple_explanation: 'אירעה שגיאה זמנית בניתוח צילום המסך. נסה לצלם שוב בצורה ברורה.'
};

export default {
  async fetch(request, env) {
    if (request.method !== 'POST') {
      return new Response(JSON.stringify({ error: 'Only POST supported' }), {
        status: 405,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      });
    }

    let body = {};
    let imageBase64 = '';
    let requestDeviceId = '';
    let requestLicenseKey = '';
    const contentType = request.headers.get('Content-Type') || '';
    try {
      if (contentType.toLowerCase().includes('multipart/form-data')) {
        const formData = await request.formData();
        const uploadedImage = formData.get('image');
        if (uploadedImage && typeof uploadedImage !== 'string' && typeof uploadedImage.arrayBuffer === 'function') {
          const bytes = new Uint8Array(await uploadedImage.arrayBuffer());
          let binary = '';
          const chunkSize = 0x8000;
          for (let offset = 0; offset < bytes.length; offset += chunkSize) {
            binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
          }
          imageBase64 = btoa(binary);
        }
        const formDeviceId = formData.get('device_id');
        const formLicenseKey = formData.get('license_key');
        requestDeviceId = typeof formDeviceId === 'string' ? formDeviceId.trim() : '';
        requestLicenseKey = typeof formLicenseKey === 'string' ? formLicenseKey.trim() : '';
      } else if (contentType.toLowerCase().includes('application/json') || !contentType) {
        body = await request.json();
        imageBase64 = typeof body?.image_base64 === 'string' ? body.image_base64.trim() : '';
        requestDeviceId = typeof body?.device_id === 'string' ? body.device_id.trim() : '';
        requestLicenseKey = typeof body?.license_key === 'string' ? body.license_key.trim() : '';
      } else {
        return new Response(JSON.stringify(FALLBACK_ALERT), {
          status: 200,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }
    } catch {
      return new Response(JSON.stringify(FALLBACK_ALERT), {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      });
    }

    const clientIP = request.headers.get('CF-Connecting-IP') || 'unknown';
    const deviceId = requestDeviceId;
    const hasValidDeviceId = deviceId.length >= 6;
    const suppliedLicense = String(request.headers.get('X-License-Key') || requestLicenseKey || '').trim().toUpperCase();
    const builtInVipKeys = ['SHOMER-FAMILY', 'SHOMER-VIP1'];
    const configuredVipKeys = String(env.VIP_KEYS || '')
      .split(',')
      .map((key) => key.trim().toUpperCase())
      .filter(Boolean);
    const activeVipKeys = [...new Set([...builtInVipKeys, ...configuredVipKeys])];
    const isVip = suppliedLicense !== '' && activeVipKeys.includes(suppliedLicense);
    if (suppliedLicense && !isVip) {
      return new Response(JSON.stringify({
        alert_title: '⚠️ קוד מנוי לא תקין',
        simple_explanation: 'קוד הגישה שהוזן אינו פעיל. בדוק שהקוד הוקלד נכון או פנה לתמיכה.'
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      });
    }

    const now = new Date();
    const yearMonth = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
    const yearMonthDay = `${yearMonth}-${String(now.getUTCDate()).padStart(2, '0')}`;
    const quotaPeriod = isVip ? yearMonthDay : yearMonth;
    const vipDigest = isVip
      ? Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(suppliedLicense))))
          .map((byte) => byte.toString(16).padStart(2, '0')).join('')
      : '';
    const quotaIdentity = isVip
      ? `vip-${vipDigest}`
      : hasValidDeviceId ? `device:${deviceId}` : `ip:${clientIP}`;
    const quotaKey = `https://quota.internal/${encodeURIComponent(quotaIdentity)}-${quotaPeriod}`;
    const quotaKvKey = `${quotaIdentity}-${quotaPeriod}`;
    const quotaTtl = isVip ? 172800 : MONTHLY_QUOTA_TTL_SECONDS;
    const quotaLimit = isVip ? 60 : MONTHLY_SCAN_LIMIT;
    const quotaCache = caches.default;
    let scanCount = 0;
    try {
      if (env.SCAN_QUOTAS) {
        const quotaData = await env.SCAN_QUOTAS.get(quotaKvKey, 'json');
        scanCount = Number.isInteger(quotaData?.count) && quotaData.count >= 0 ? quotaData.count : 0;
      } else {
        const cachedQuota = await quotaCache.match(quotaKey);
        if (cachedQuota) {
          const quotaData = await cachedQuota.json();
          scanCount = Number.isInteger(quotaData.count) && quotaData.count >= 0 ? quotaData.count : 0;
        }
      }
    } catch {
      return new Response(JSON.stringify(FALLBACK_ALERT), {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      });
    }

    if (scanCount >= quotaLimit) {
      const quotaMessage = isVip
        ? { alert_title: '🔒 הגעת למגבלת הבדיקות היומית', simple_explanation: 'הגעת למכסת 60 הבדיקות היומית לקוד המנוי. נסה שוב מחר.' }
        : { alert_title: '🔒 ניצלת את 3 הבדיקות החינמיות החודש', simple_explanation: 'הגעת למכסת 3 הבדיקות החודשיות בחינם. לשדרוג לבדיקות ללא הגבלה היכנסו ל-shomer-harosh.netlify.app/pro' };
      return new Response(JSON.stringify(quotaMessage), {
        status: 429,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      });
    }

    try {

      if (!imageBase64) {
        return new Response(JSON.stringify({ error: 'Missing image_base64' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }
      const mimeType = imageBase64.startsWith('iVBOR') ? 'image/png' : 'image/jpeg';

      const apiKey = env.GEMINI_API_KEY;

      if (!apiKey) {
        return new Response(JSON.stringify({ error: 'GEMINI_API_KEY missing' }), {
          status: 500,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }

      const generationConfig = {
        temperature: 0,
        responseMimeType: 'application/json',
        thinkingConfig: { thinkingBudget: 0 },
        maxOutputTokens: 350
      };
      const requestBody = JSON.stringify({
        systemInstruction: {
          parts: [{
            text: `You are a careful Hebrew anti-scam assistant for Israeli seniors. Identify the sender/brand from the visible sender name, logo, message text, and URL, then classify based on the actual message and requested actions.

STRICT RISK POLICY: Thoroughly analyze all visible message text and context, sender name/identity, brand, link text/domain, requested action, and surrounding conversation. Consider legitimate delivery companies, utility/electricity providers (including חשמל ישיר), official notifications, calendar/appointment reminders, and personal services. A link—including tinyurl, bit.ly, or another short link—does NOT by itself indicate phishing. If the sender, wording, and context clearly indicate an ordinary legitimate delivery, utility, appointment, official administrative update, or personal service, classify SAFE and provide an informative Hebrew title/explanation; do not warn merely because it includes a link. Return DANGER only for clear evidence of phishing/fraud: explicit credential or payment-detail harvesting, fake bank/pension/insurance claims, or suspicious lookalike domains attempting to steal personal data. Use WARNING for suspicious or unclear content that does not meet the DANGER threshold. Return SAFE for clearly legitimate services/administrative messages, exact official Israeli domains, ordinary recognized-brand marketing, and benign personal chats.
STRICT ROOT-DOMAIN VERIFICATION: Whitelisted domains are SAFE only if the parsed URL root/registrable domain matches the listed official domain character-for-character; a familiar brand name or sender label alone is never proof. Treat extra hyphens, inserted words, misspellings, lookalike domains, and misleading subdomains as HIGH-RISK DANGER, including israelpost-il.com, bitpay-verify.co.il, gaashwd-tracking.com, and bitpay.co.il.scam.com. A subdomain is not an exact root-domain match; parse the URL host carefully. If a message requests card number, CVV, bank login credentials, one-time codes, or a small customs/release fee through an unfamiliar or mismatched link, classify DANGER even if the sender name appears official. Never let a whitelist override a deceptive host or credential/payment request.

LINK CONTEXT RULE: Never classify a message as phishing solely because it contains a URL, including tinyurl, bit.ly, or other shortened links. Evaluate the full screenshot context: sender identity, service/brand, wording, link text/domain if visible, and requested action. Clearly contextualized ordinary delivery tracking, electricity/utility service (including חשמל ישיר), official notices, calendar or appointment reminders, and personal-service updates should be SAFE with an informative Hebrew headline and explanation, even if a link is present. Use DANGER only when the content explicitly seeks credentials/payment data or clearly impersonates a bank, pension/insurance provider, authority, or service through a suspicious lookalike domain. Use WARNING only for genuinely suspicious or unclear cases, not for a link alone.



ISRAELI DELIVERY/RETAIL WHITELIST: Treat gaashwd.com (געש וורלדווייד), cheetahdelivery.co.il, HFD, israelpost.co.il, Pairzon/pairzon.com (Hastok/הסטוק), and ezcount.co.il as legitimate Israeli logistics, delivery, retail, and receipt providers for standard tracking messages and digital receipts. Tracking IDs such as TMU... and PUDO, in a normal delivery context with a plausible sender/official domain, are legitimate SAFE updates. Distinguish real tracking from fake customs or delivery-fee phishing. A tracking number or link alone is not fraud; DANGER requires clear evidence of impersonation, credential/card theft, or a fabricated fee scam.


ISRAELI RECEIPT RULE: Recognize Pairzon (pairzon.com), Comax (comax.co.il), EZCount (ezcount.co.il), Green Invoice (greeninvoice.co.il), iCount (icount.co.il), חשמל ישיר, and Hastok (הסטוק) as legitimate Israeli retail, invoicing, receipt, warranty, or service platforms. A standard digital receipt, invoice, warranty document, or privacy-policy link sent after a store purchase is SAFE when the message context is a normal purchase follow-up—even if it includes a tinyurl.com privacy-policy link or another shortener. Explain in Hebrew that it is a standard digital receipt/document from the named store. Do not flag it merely because it contains a privacy link, receipt link, or warranty document.


PERSONAL CHAT RULE: Personal/private SMS or WhatsApp conversations between individuals MUST be SAFE when they contain ordinary casual conversation, personal replies, prayer mentions, emojis, or normal text and have no external links, financial requests, credential requests, or phishing hooks. Do not treat personal names, prayer, greetings, or casual language as suspicious. For a SAFE personal chat, use a calm, reassuring Hebrew headline such as 'התכתבות אישית רגילה' (2-5 words); explain simply that this is a normal personal conversation.

Recognized Israeli brands include Domino's / דומינו'ס / דומינוס, Papa John's / פאפא ג'ונס, McDonald's / מקדונלד'ס, Super-Pharm / סופר-פארם, Shufersal / שופרסל, Rami Levy / רמי לוי, Fox / פוקס, Castro / קסטרו, Wolt / וולט, Ten Bis / תן ביס, Cibus / סיבוס, KSP, Ivory / אייבורי, Bug / באג, Bezeq, Partner, Cellcom, Pelephone, HOT, YES, Clalit, Maccabi, Meuhedet, Leumit, bit, Max, Isracard, Cal, Bank Hapoalim, Leumi, Discount, and Mizrahi-Tefahot.

IMPORTANT SAFE RULE: Classify ordinary promotional/marketing SMS, discounts, coupons, delivery updates, and account notices from a recognized Israeli brand listed above as SAFE—even when they contain Israeli SMS tracking/short links w.ems.to, hasr.co.il, katzr.net, shol.link, i4u.me, or did.li, together with a 10-digit unsubscribe number (for example 050-1234567). These links and unsubscribe numbers are commonly used by legitimate Israeli SMS platforms. Their presence MUST NOT raise a recognized-brand promo to WARNING or DANGER. WARNING is reserved for unknown/unrecognized senders with shortened links. DANGER is reserved for clear phishing or impersonation. For a recognized sender, only classify as DANGER if the message clearly impersonates another entity or requests passwords, payment-card information, one-time codes, or other sensitive credentials in a deceptive context.

Official Israeli financial and government domains are legitimate and MUST be SAFE when the URL hostname exactly matches bitpay.co.il, max.co.il, isracard.co.il, cal-online.co.il, bankhapoalim.co.il, leumi.co.il, discountbank.co.il, mizrahi-tefahot.co.il, or gov.il. Exact host matches remain legitimate with normal paths such as /app/transaction-info. A lookalike or different hostname is not an exact match.

Use WARNING ONLY for unknown or unrecognized senders that use shortened links or make suspicious prize/gift claims. A recognized Israeli brand's normal promo with a listed local marketing link and unsubscribe number remains SAFE. Use DANGER ONLY for clear phishing/impersonation, including fake Israel Post (דואר ישראל) customs-fee messages; fake כביש 6, פנגו, ביטוח לאומי, or רשות המסים; and bank/credit-card impersonation using lookalike domains. Never classify merely unfamiliar content as DANGER.

For SAFE messages, use a calm, reassuring explanation and never say 'כדאי להיזהר' or 'לא ללחוץ סתם'. Always explicitly name the identified brand in both headline and simple_explanation. The headline must be a specific, non-generic Hebrew title of 2-5 words naming that brand, for example 'הודעת מבצע מדומינוס פיצה'. Never output 'בדיקת הודעה' or 'שולח לא מזוהה' when a listed brand is visible. If no brand is truly identifiable, briefly state that the sender could not be identified; do not invent a brand.

Return ONLY a valid JSON object with exactly these internal keys in this order: risk_level, headline, simple_explanation. The Worker uses risk_level to set alert_title and returns only alert_title, headline, simple_explanation. For SAFE messages, write a calm, reassuring explanation with no warning language. For receipts, explicitly say it is a standard digital receipt/document from the named store. For WARNING or DANGER messages, use a clear cautionary explanation; never pair a warning explanation with SAFE. headline must be a specific Hebrew title of 2-5 words naming the identified store/service; use 'קבלה דיגיטלית מהסטוק' for an identified Hastok receipt and 'התכתבות אישית רגילה' for benign personal chats. Never use a generic title when a brand/service is identifiable. No markdown or prose outside JSON.`
          }]
        },
        contents: [{
          parts: [{
            text: 'Analyze the attached image for Israeli scam/phishing threats. Output only the required JSON object.'
          }, {
            inlineData: { mimeType, data: imageBase64 }
          }]
        }],
        generationConfig
      });

      const fetchWithTimeout = async (url, options, timeoutMs) => {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), timeoutMs);
        try {
          const response = await fetch(url, { ...options, signal: controller.signal });
          const text = await response.text();
          return { response, text };
        } catch (error) {
          return { error, timedOut: controller.signal.aborted };
        } finally {
          clearTimeout(timeout);
        }
      };
      const callModel = (model) => fetchWithTimeout(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: requestBody },
        2500
      );
      const parseJson = (text) => {
        try { return JSON.parse(text); } catch { return null; }
      };
      const fetchStartedAt = Date.now();
      let result = null;
      let response = null;
      let rawResponse = '';
      let data = null;
      const attemptedModels = new Set();

      if (cachedWorkingModelName && cachedWorkingModelName !== 'gemini-3.8-flash') {
        const cachedName = cachedWorkingModelName;
        attemptedModels.add(cachedName);
        result = await callModel(cachedName);
        response = result.response || null;
        rawResponse = result.text || '';
        data = parseJson(rawResponse);
        if (response?.status === 200) {
          // Reuse the cached model without discovery.
        } else {
          cachedWorkingModelName = null;
          response = null;
        }
      }

      if (!response) {
        const modelsResult = await fetchWithTimeout(
          `https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`,
          {},
          1500
        );
        const modelsData = modelsResult.response?.ok ? parseJson(modelsResult.text || '') : null;
        const excluded = /(embedding|tts|image|audio|live|computer-use)/i;
        const candidates = (modelsData?.models || [])
          .filter((model) => {
            const fullName = String(model?.name || '');
            const name = fullName.replace(/^models\//, '');
            const lower = name.toLowerCase();
            return lower.includes('flash') &&
              !lower.startsWith('gemini-1.') &&
              !lower.startsWith('gemini-2.0') &&
              fullName !== 'models/gemini-2.5-flash' &&
              !excluded.test(lower) &&
              model?.supportedGenerationMethods?.includes('generateContent');
          })
          .map((model) => ({ ...model, modelId: String(model.name).replace(/^models\//, '') }))
          .sort((a, b) => {
            const priority = (name) => {
              if (name === 'gemini-2.5-flash-lite') return 0;
              if (name === 'gemini-flash-latest') return 1;
              if (name === 'gemini-3.8-flash') return Number.MAX_SAFE_INTEGER;
              return 2;
            };
            const order = priority(a.modelId) - priority(b.modelId);
            return order || b.modelId.localeCompare(a.modelId, undefined, { numeric: true });
          });

        for (const model of candidates) {
          if (attemptedModels.has(model.modelId)) continue;
          attemptedModels.add(model.modelId);
          result = await callModel(model.modelId);
          if (result.response?.status === 200) {
            cachedWorkingModelName = model.modelId;
            response = result.response;
            rawResponse = result.text || '';
            data = parseJson(rawResponse);
            break;
          }
          response = result.response || null;
          rawResponse = result.text || String(result.error || 'Model request timed out');
          data = parseJson(rawResponse);
        }
      }

      const elapsedMs = Date.now() - fetchStartedAt;
      if (!response || response.status !== 200) {
        return new Response(JSON.stringify({ error: data?.error?.message || rawResponse || 'No compatible flash model succeeded', ms: elapsedMs }), {
          status: response?.status || (result?.timedOut ? 504 : 502),
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }

      const rawText = data?.candidates?.[0]?.content?.parts?.map((part) => part?.text || '').join('') || '';
      if (!response.ok || !rawText) {
        const googleErrorMessage = data?.error?.message || rawResponse;
        return new Response(JSON.stringify({ error: googleErrorMessage, ms: elapsedMs }), {
          status: response.status,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }

      const cleaned = rawText.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();
      let parsed;
      try { parsed = JSON.parse(cleaned); } catch { parsed = null; }
      if (!parsed || typeof parsed !== 'object' ||
          typeof parsed.simple_explanation !== 'string' || !parsed.simple_explanation.trim()) {
        return new Response(JSON.stringify(FALLBACK_ALERT), {
          status: 200,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }

      const riskLevel = String(parsed.risk_level || '').toUpperCase();
      const validRisk = ['DANGER', 'WARNING', 'SAFE'].includes(riskLevel) ? riskLevel : 'WARNING';
      const rawHeadline = String(parsed.headline || '').trim();
      const explanation = String(parsed.simple_explanation || '').slice(0, 180);
      const brandContext = `${rawHeadline} ${explanation}`.toLowerCase();
      const recognizedBrand = [
        { aliases: ['דומינו', 'דומינוס', "domino"], title: 'דומינוס פיצה' },
        { aliases: ['פאפא ג', 'papa john'], title: 'פאפא גונס' },
        { aliases: ['מקדונלד', 'mcdonald'], title: 'מקדונלדס' },
        { aliases: ['סופר-פארם', 'סופר פארם', 'super-pharm'], title: 'סופר פארם' },
        { aliases: ['שופרסל', 'shufersal'], title: 'שופרסל' },
        { aliases: ['רמי לוי', 'rami levy'], title: 'רמי לוי' },
        { aliases: ['פוקס', 'fox'], title: 'פוקס' },
        { aliases: ['קסטרו', 'castro'], title: 'קסטרו' },
        { aliases: ['וולט', 'wolt'], title: 'וולט' },
        { aliases: ['תן ביס', 'ten bis'], title: 'תן ביס' },
        { aliases: ['סיבוס', 'cibus'], title: 'סיבוס' },
        { aliases: ['ksp'], title: 'קיי אס פי' },
        { aliases: ['אייבורי', 'ivory'], title: 'אייבורי' },
        { aliases: ['בזק', 'bezeq'], title: 'בזק' },
        { aliases: ['פרטנר', 'partner'], title: 'פרטנר' },
        { aliases: ['סלקום', 'cellcom'], title: 'סלקום' },
        { aliases: ['פלאפון', 'pelephone'], title: 'פלאפון' },
        { aliases: ['הוט', 'hot'], title: 'הוט' },
        { aliases: ['כללית', 'clalit'], title: 'כללית' },
        { aliases: ['מכבי', 'maccabi'], title: 'מכבי' },
        { aliases: ['מאוחדת', 'meuhedet'], title: 'מאוחדת' },
        { aliases: ['לאומית', 'leumit'], title: 'לאומית' },
        { aliases: ['מקס', 'max'], title: 'מקס' },
        { aliases: ['ישראכרט', 'isracard'], title: 'ישראכרט' },
        { aliases: ['כאל', 'cal-online'], title: 'כאל' },
        { aliases: ['הפועלים', 'bank hapoalim'], title: 'בנק הפועלים' },
        { aliases: ['לאומי', 'leumi'], title: 'בנק לאומי' },
        { aliases: ['דיסקונט', 'discount'], title: 'בנק דיסקונט' },
        { aliases: ['מזרחי', 'mizrahi'], title: 'בנק מזרחי' },
        { aliases: ['pairzon.com', 'pairzon'], title: 'פיירזון' },
        { aliases: ['comax.co.il', 'comax'], title: 'קומקס' },
        { aliases: ['ezcount.co.il', 'ezcount'], title: 'איזיקאונט' },
        { aliases: ['greeninvoice.co.il', 'green invoice'], title: 'חשבונית ירוקה' },
        { aliases: ['icount.co.il', 'icount'], title: 'אייקאונט' },
        { aliases: ['חשמל ישיר', 'direct electricity'], title: 'חשמל ישיר' },
        { aliases: ['הסטוק', 'hashtok', 'hastok'], title: 'הסטוק' },
        { aliases: ['pairzon.com', 'pairzon'], title: 'פיירזון' },
        { aliases: ['comax.co.il', 'comax'], title: 'קומקס' },
        { aliases: ['ezcount.co.il', 'ezcount'], title: 'איזיקאונט' },
        { aliases: ['greeninvoice.co.il', 'green invoice'], title: 'חשבונית ירוקה' },
        { aliases: ['icount.co.il', 'icount'], title: 'אייקאונט' }
      ].find((brand) => brand.aliases.some((alias) => brandContext.includes(alias)));
      const headlineWords = rawHeadline.split(/\s+/).filter(Boolean);
      const isValidHebrewHeadline = headlineWords.length >= 2 && headlineWords.length <= 5 &&
        headlineWords.every((word) => /[\u0590-\u05FF]/.test(word)) &&
        (!recognizedBrand || brandContext.includes(recognizedBrand.title) || recognizedBrand.aliases.some((alias) => rawHeadline.toLowerCase().includes(alias)));
      const headline = isValidHebrewHeadline ? rawHeadline.slice(0, 80) :
        recognizedBrand ? `הודעת מבצע ${recognizedBrand.title}` : 'שולח לא מזוהה';

      const alertTitle = validRisk === 'SAFE'
        ? '✅ ההודעה נראית בטוחה'
        : '🛑 עצור! חשד להונאה';
      const personalChat = /התכתבות|שיחה אישית|הודעה אישית/.test(brandContext);
      const finalHeadline = personalChat && validRisk === 'SAFE'
        ? 'התכתבות אישית רגילה'
        : headline;
      const finalExplanation = explanation || (validRisk === 'SAFE'
        ? 'ההודעה נראית רגילה ובטוחה.'
        : 'ההודעה חשודה, אל תמסור פרטים ואל תלחץ על הקישור.');

      if (typeof alertTitle !== 'string' || !alertTitle.trim() ||
          typeof finalExplanation !== 'string' || !finalExplanation.trim()) {
        return new Response(JSON.stringify(FALLBACK_ALERT), {
          status: 200,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }

      const updatedQuota = { count: scanCount + 1 };
      try {
        if (env.SCAN_QUOTAS) {
          await env.SCAN_QUOTAS.put(quotaKvKey, JSON.stringify(updatedQuota), {
            expirationTtl: quotaTtl
          });
        } else {
          await quotaCache.put(quotaKey, new Response(JSON.stringify(updatedQuota), {
            headers: {
              'Content-Type': 'application/json',
              'Cache-Control': `public, max-age=${quotaTtl}`
            }
          }));
        }
      } catch {
        return new Response(JSON.stringify(FALLBACK_ALERT), {
          status: 200,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }

      const isDanger = validRisk === 'DANGER';
      console.log(JSON.stringify({
        event: 'scan_result',
        status: isDanger ? 'danger' : 'safe',
        title: alertTitle,
        timestamp: new Date().toISOString()
      }));

      return new Response(JSON.stringify({
        alert_title: alertTitle,
        headline: finalHeadline,
        simple_explanation: finalExplanation
      }), {
        headers: {
          'Content-Type': 'application/json',
          'Access-Control-Allow-Origin': '*'
        }
      });
    } catch (error) {
      return new Response(JSON.stringify(FALLBACK_ALERT), {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      });
    }
  }
};
