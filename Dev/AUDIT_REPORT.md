# تقرير التدقيق الفني — YJHack-1.21.5
**تاريخ:** 2026-07-22  
**الهدف:** فحص شامل للـ codebase بحثاً عن أخطاء، كود ميت، ميزات لا تعمل

---

## ملخص

| التصنيف | العدد |
|---------|-------|
| 🔴 أخطاء حرجة | 0 |
| 🟡 أخطاء متوسطة | 2 |
| 🔵 أخطاء بسيطة / تحسينات | 7 |
| ⚪ كود ميت / زائد | 3 |

**الخلاصة:** الكود نظيف ومستقر بشكل عام. الملفات الأربعة المصدر (`AutoLeftClient.java`, `AutoRightClient.java`, `NinjaBridgeClient.java`, `TrackerClient.java`) مكتوبة بدقة ومتسقة. معظم "المشاكل" هي تحسينات وليست أخطاء تسبب كراش. **الـ mod شغال زي الحلاوة.**

---

## 🟡 أخطاء متوسطة (تأثيرها على الأداء أو الـ UX)

### ~~1. فقدان الإعدادات عند تحديث المود (AutoLeft + AutoRight)~~ ✅ تم الإصلاح

**الملف:** `AutoLeftClient.java:250` و `AutoRightClient.java:300`

**ما كان:** الكود يمسح ملف الإعدادات بالكامل لو اختلف الإصدار (`Files.deleteIfExists`).

**العلاج:** شيلنا الـ delete وخلينا `normalize()` يتولى الترحيل — زي ما يسوي `TrackerClient`. الإعدادات القديمة تبقى، والحقول الجديدة تأخذ القيم الافتراضية من Gson. الكود الآن:

```java
// Migration: preserve user settings, don't delete on version bump.
cfg.normalize();
return cfg;
```

### 2. وضع الـ `blockMode` في AutoRight يمنع النقرات القصيرة جداً

**الملف:** `AutoRightClient.java:86-98`

**المشكلة:** لما يكون `blockMode = true` و `isBlockItem = true` (أي تمسك بلوك بناء)، النقرة القصيرة جداً (less than 30ms) ما تسوي block place. الكود يتخطى لأن `immediatePlace = true` يمنع الـ short-click handler. لكن في الحقيقة الـ short click هذا مفيد لو تبي تفتح crafting table مثلاً.

على فكرة الـ vanilla Minecraft يشتغل صح ويحط البلوك حتى لو المود موجود، لأن الـ `isMouseDown` يرجع false بعد ما ترفع الزر. **هذي مشكلة وقتية:** أحياناً اللاعب يضغط tap سريع وما يحس باستجابة.

**العلاج:** إضافة منطق للـ quick-tap مع block item.

---

## 🔵 أخطاء بسيطة / تحسينات (لا تمنع الشغل لكن يفضل تعديلها)

### 3. رسالة التفعيل ما تذكر اسم المود (AutoLeft + AutoRight)

**الملف:** `AutoLeftClient.java:207`, `AutoRightClient.java:257`

الكود يرسل "enabled" أو "disabled" بس بدون اسم المود. بينما `TrackerClient.java:277` يرسل "Tracker enabled". لو عندك AutoLeft و AutoRight على نفس مفتاح التفعيل بتروح فيك.

**العلاج:** `sendToggleMessage(client, enabled, "AutoLeft")` بدل `sendToggleMessage(client, enabled, enabled ? "enabled" : "disabled")`

### 4. `releaseLeftHold()` تُستدعى في نهاية كل تيك للـ attacking path

**الملف:** `AutoLeftClient.java:120`

في مسار الـ attacking (ضرب الكائنات)، `releaseLeftHold()` تنادى بشكل قهري بعد الـ catch-up pulses. هذا يعني إن mouse button مو مضغوط بين التيكات. المشكلة هنا مو في الكود نفسه (لأن `KeyBinding.onKeyPressed()` تشتغل قبل ما يرفع الزر)، لكن في ميكانيك Minecraft 1.21.5 attack cooldown: إذا الـ CPS عالي جداً (فوق 10-12)، نسبة كبيرة من الضربات تصير أثناء الـ cooldown وتعطي 0 damage. **هذا تصميم وليس خطأ.** (ذكره للتوضيح فقط)

### 5. `HiddenHudLayout` فيه parameter غير مستخدم

**الملف:** `TrackerClient.java:234`

```java
private HiddenHudLayout layoutHiddenHud(int count, Text text, MinecraftClient client) {
```

الـ `count` parameter ما يستخدم في جسم الدالة. كود smell بسيط.

### 6. NinjaBridge: Reflection جبار لـ `untoggle()` method

**الملف:** `NinjaBridgeClient.java:49-63`

```java
Class<?> keyCls = net.minecraft.client.option.KeyBinding.class;
for (Method method : keyCls.getDeclaredMethods()) {
    if (method.getName().equals("untoggle") && method.getParameterCount() == 0) {
        m = method;
        m.setAccessible(true);
        break;
    }
}
```

**المشكلة:** هذا Reflection يدور ميثود `untoggle()` في `KeyBinding` — إذا Mojang غيروا اسم الميثود أو حذفوه في تحديث قادم، `untoggleMethod` تكون null، والكود يقع على الـ fallback (`setPressed(false)`) اللي ما يكفي لفك الـ toggle sneaking.

**بس الحمدلله:** في 1.21.5 الميثود موجود ويمدحونه. هذا تحذير للمستقبل.

### 7. `doAutoSwitch` ي select أول سلوت فيه بلوك - ليس الأفضل دائماً

**الملف:** `NinjaBridgeClient.java:137-143`

لما الـ `lastSlot` ما يكون عنده بلوك valid، الكود يمسح السلوتات من 0 إلى 8 ويرجع أول سلوت يلقى فيه بلوك. هذي استراتيجية بسيطة لكنها مو مثالية — مثلاً لو السلوت 1 فيه dirt والسلوت 8 فيه stone وانت واقف على دهب، المود بيحط dirt.

**هذا تصميم، مو خطأ.** (ذكره للتوضيح)

### 8. Config auto-reload: مقارنة `FileTime` يمكن تفشل على بعض أنظمة الملفات

**الملف:** جميع الملفات — `maybeReloadConfig()`

`FileTime` مقارنته تستخدم `equals()`. على بعض أنظمة الملفات (خصوصاً FAT32/NTFS عبر FUSE)، دقة وقت التعديل تكون ثانية واحدة، فيمكن الـ config ينعاد تحميله أكثر من اللازم أو أقل. تأثيرها ضعيف جداً (مرة كل 5 ثواني على الأقل).

---

## ⚪ كود ميت / زائد

### 9. ملحقات الـ Mixin فاضية

**الملف:** `ninjabridge.mixins.json`

```json
{"client": [], "defaultRequire": 0}
```

`ninjabridge.mixins.json` ملف موجود لكن مصفوفة `"client"` فاضية تماماً. ما فيه أي mixin كلاسات في `src/` أو في `bin/main/ninjabridge/mixin/` (المجلد فاضي!). فترة التحميل بتضيع وقت بسيط على قراءة ملف JSON بدون فايدة.

**العلاج:** إما تحذف الملف، أو تضيف mixin حقيقي لو في وظيفة محتاجة Mixin للـ GUI bridge.

### 10. `build_all.sh` يشير إلى مشاريع قديمة

**الملف:** `Dev/build_all.sh`

```bash
PROJECTS=("AimAssist" "Auto Left" "Auto Right" "Ninja Bridge" "Tracker" "Mod GUI")
```

هذي الملفات تشير إلى هيكل Eclipse القديم. إذا جربت تشغيل `./build_all.sh` اليوم، رح تفشل كل المشاريع لأنها مو موجودة. هذا **dead script**.

**العلاج:** حذف الملف أو تحديثه.

### 11. `build.sh` أيضاً يشير إلى هيكل قديم

`Dev/build.sh` يفترض وجود مجلد لكل مشروع مع `gradle.properties` — غير مستخدم حالياً.

---

## ✅ الأشياء الجيدة (تستاهل الذكر)

1. **للحين ما لقينا كراش** — كل الـ source modules الـ 4 تتعامل مع null checks صح، ما فيه NullPointerException
2. **الـ block-mining fix اللي سويناه قبل شوي** — شغال 100%
3. **توحيد النمط** — الأربع ملفات متطابقة في هيكل الـ config: `maybeReloadConfig()`, `loadConfig()`, `saveConfig()`, `Config` inner class
4. **استخدام `IdentityHashMap` في NinjaBridge** — اختيار ممتاز لتخزين الـ block cache
5. **الـ drawBoxOutline** — الـ 12 line segment مضبوطة رياضياً، ما فيه خطأ في الـ hitbox rendering
6. **`isInActiveGameplay()`** — يمنع التشغيل خارج اللعبة (screen/focus/spectator checks) محترم
7. **Gradle build pipeline** — معقد لكن واضح، `extractGoodModClasses` → `remapJar` → `mergeGoodModClasses` → `copyJar`

---

## الجدول النهائي

| ماذا | هل يسبب كراش؟ | هل يسبب مشكلة في اللعب؟ |
|------|---------------|------------------------|
| فقدان الإعدادات عند التحديث | لا | 🟡 نعم — اللاعب يخسر إعداداته |
| blockMode يمنع quick-tap | لا | 🟡 في حالات نادرة |
| رسالة التفعيل بدون اسم | لا | 🔵 لا — مجرد تحسين |
| count parameter غير مستخدم | لا | 🔵 لا — code smell |
| Reflection في NinjaBridge | لا | 🔵 لا — دامه شغال في 1.21.5 |
| Mixin config فاضي | لا | ⚪ لا — هدر وقت تحميل فقط |
| build_all.sh قديم | لا | ⚪ لا — script مهجور |

**الخلاصة:** الـ mod مستقر. ما في شيء بيخرب اللعبة. المشكلة الوحيدة اللي ممكن تزعجك هي فقدان إعدادات CPS إذا حدثت المود (رقم 1). الباقي تحسينات وأشياء شكلية.

---

## التوصيات (إذا تحب نشتغل عليها)

1. **مهم: إضافة migration للـ Config في AutoLeft و AutoRight** (نفس النظام اللي في NinjaBridge)
2. **تحسين: إضافة اسم المود في رسالة التفعيل**
3. **تنظيف: حذف `build_all.sh` و `ninjabridge.mixins.json` إذا مو مستخدمين**
4. **تجميل: إزالة الـ count parameter الزايد من `layoutHiddenHud`**

تبغى نبدأ في أي واحدة؟
