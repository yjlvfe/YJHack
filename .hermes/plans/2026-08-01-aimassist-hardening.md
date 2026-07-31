# AimAssist Anti-Cheat Hardening Plan

## المبدأ
**ما في أي نسبة خطأ.** الهدف مش إنه يغلط، الهدف إنه يكون **بشري أكثر** في طريقة الحركة فقط.

## التعديلات

### 1. توسيع مدى المسافة (Range)
- `ABSOLUTE_MAX_DISTANCE`: 3.5 → 6.0 (سقف جديد)
- السلايدر في GUI: 1.0 إلى 6.0 بدل 1.0 إلى 3.5
- المستخدم يتحكم — يحط اللي يبغاه

### 2. Humanized Movement (تبديل كامل لـ applyAimAssist)
بدل الـ S-curve الرياضي المثالي، نستخدم:
- **Micro-wobble**: إضافة ±(0.1°–0.5°) عشوائي لـ yaw و pitch كل tick
- **Inconsistent speed**: الـ speed يختلف ±15% كل tick (أسرع مرة، أبطأ مرة)
- **Organic curve**: بدل sCurve المثالي، نستخدم path فيه تسارع وتباطؤ طبيعي

### 3. Target Acquisition Delay
- أول ما يلاقي هدف جديد: تأخير 50-200ms قبل بدء التصويب (random كل مرة)
- هذا يحاكي وقت رد الفعل البشري

### 4. Tracking Imperfection  
- الـ aim point يتحرك شوية على جسم اللاعب (مش نفس النقطة بالضبط)
- مرة يصوب على الصدر، مرة على الكتف، مرة على الرأس
- كل هذا **بدون ما يغلط الهدف** — مجرد تغيير مكان التصويب على نفس الجسم

### 5. GUI
- Humanized Aim toggle (افتراضي: ON)
- Range slider: 1.0 إلى 6.0 (بدل 1.0 إلى 3.5)

## ملفات سيتم تعديلها
- `AimAssistClient.java` — applyAimAssist() + humanized toggle
- `AimAssistRangePolicy.java` — رفع السقف لـ 6.0
- `ModGuiClient.java` — range slider + humanized toggle في AimAssistScreen
- `HumanizedAimAssist.java` (جديد) — محرك الحركة البشرية
