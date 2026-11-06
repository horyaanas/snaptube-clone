# SnapTube Modern Clone (أندرويد حديث مطابق لسناب تيوب)

مشروع أندرويد متكامل تم بناؤه استناداً إلى نتائج الهندسة العكسية الدقيقة لملف `SnapTube_tube_snaptubecom.apk`. يعتمد المشروع على معمارية الأندرويد الحديثة (Modern Android Architecture) مع Jetpack Compose و Material 3 و Kotlin Coroutines و Media3/ExoPlayer.

---

## 📱 المميزات والواجهات المطابقة لسناب تيوب

1. **الواجهة والهوية البصرية (SnapTube Visual Identity):**
   - الألوان الرسمية: الأصفر الذهبي المميز (`#FFBB00`) والأسطح الداكنة المريحة للعين (Dark AMOLED Surface `#121212`).
   - شريط البحث العلوي التفاعلي للبحث المباشر أو لصق الروابط.
   - شريط التنقل السفلي المكون من 5 تبويبات مطابقة:
     - **الرئيسية (Home):** شبكة المواقع السريعة (Speed Dial)، تصنيفات الفيديو (Trending, Music, Gaming)، وبطاقات الفيديو مع زر التحميل الأصفر الدائري.
     - **المتصفح المدمج (Browser):** متصفح ويب مع كشف تلقائي لتدفقات الفيديو وظهور زر التحميل العائم (Floating FAB) عند العثور على وسائط.
     - **ملفاتي (Downloads):** تقسيم فوري بين التنزيلات الجارية (قيد التحميل) والملفات المكتملة مع مؤشرات السرعة اللحظية (MB/s).
     - **الأدوات (Tools):** أداة حفظ حالات واتساب (WhatsApp Status Saver)، الخزنة المشفرة برمز PIN (Private Vault)، وأداة تنظيف وتسريع الهاتف.
     - **أنا / الإعدادات (Me):** خيارات تسريع التنزيل متعدد المسارات، التنزيل عبر Wi-Fi فقط، والوضع الليلي.

2. **نافذة اختيار الدقة والصيغة (Choose Format Bottom Sheet):**
   - مطابقة تامة لنافذة سناب تيوب الشهيرة عند النقر على أي فيديو:
     - **قسم الموسيقى (Music):** MP3 (128K), MP3 (160K), M4A (128K) مع عرض الحجم التقديري.
     - **قسم الفيديو (Video):** 360P, 480P, 720P HD, 1080P FHD.

3. **محرك التنزيل عالي الأداء (Multi-threaded Chunked Downloader):**
   - استئناف التنزيل من نقطة التوقف عبر HTTP Range Headers.
   - خدمة خلفية (`DownloadForegroundService`) مع إشعار حي يوضح نسبة التقدم وسرعة النقل.
   - دعم الإيقاف المؤقت والاستئناف والإلغاء وحذف الملفات.

4. **مشغل الوسائط والخلفية:**
   - جاهز للربط مع `AndroidX Media3` للتشغيل في الخلفية والشاشة العائمة (Picture-in-Picture).

---

## 🏗️ هيكلية المشروع (Project Architecture)

```
app/src/main/
├── AndroidManifest.xml
├── java/com/snaptube/clone/
│   ├── SnapTubeApp.kt                   # إعداد قنوات الإشعارات والخدمات
│   ├── data/
│   │   ├── model/VideoModel.kt          # نماذج البيانات (Video, Format, DownloadTask)
│   │   └── extractor/MediaExtractor.kt  # محرك استخراج روابط الفيديو والتحليل
│   ├── download/
│   │   ├── DownloadManager.kt           # محرك التنزيل متعدد المسارات والاستئناف
│   │   └── DownloadForegroundService.kt # خدمة التنزيل في شريط الإشعارات
│   ├── player/
│   │   └── AudioPlayerService.kt        # مشغل الوسائط في الخلفية (Media3)
│   └── ui/
│       ├── MainActivity.kt              # النشاط الرئيسي وإدارة التبويبات
│       ├── theme/                       # سمات وألوان سناب تيوب الرسمية
│       ├── components/                  # المكونات المشتركة (شريط البحث، بطاقة الفيديو، نافذة الصيغ)
│       ├── home/HomeScreen.kt           # شاشة الاستكشاف والمواقع الشهيرة
│       ├── browser/BrowserScreen.kt     # المتصفح مع زر التحميل العائم
│       ├── downloads/DownloadsScreen.kt # شاشة إدارة الملفات والتنزيلات
│       ├── tools/ToolsScreen.kt         # شاشة الأدوات (حالات واتساب، الخزنة، التسريع)
│       └── me/MeScreen.kt               # شاشة الإعدادات والملف الشخصي
└── res/
    ├── values/                          # الألوان والنصوص باللغة الإنجليزية
    └── values-ar/                       # التعريب الشامل المطابق لواجهات سناب تيوب
```

---

## 🚀 كيفية البناء والتشغيل

1. **عبر Android Studio:**
   - افتح المجلد كـ Android Project.
   - قم بمزامنة الـ Gradle واضغط `Run`.

2. **عبر سطر الأوامر (Gradle):**
   ```bash
   ./gradlew assembleDebug
   ```
   سيتم توليد ملف الـ APK في مسار:
   `app/build/outputs/apk/debug/app-debug.apk`
