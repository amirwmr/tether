# کلارک | Clark

**تماس و پیامک سیم‌کارت اندروید، روی آیفون یا یک گوشی اندرویدی دیگر.**

کلارک به گوشی اندرویدیِ سیم‌کارت‌دار وصل می‌شود تا بتوانید از گوشی دوم تماس بگیرید، پیامک بفرستید و مخاطبان و سابقهٔ تماس را ببینید. ارتباط مستقیم از طریق بلوتوث برقرار می‌شود و پس از جفت‌سازی با QR، داده‌ها بین دو دستگاه رمزنگاری سرتاسری می‌شوند.

## وضعیت پروژه

کلارک در حال توسعه است. [تغییر نام و متن‌های داخل اپ](https://github.com/ashkan0033/tether/pull/1) در پیش‌نویس بررسی قرار دارد و این مخزن هنوز فایل نصب کلارک منتشر نکرده است. سازگاری تماس صوتی و پایداری اتصال باید روی گوشی واقعی بررسی شوند.

| بخش | مسیر | کاربرد |
| --- | --- | --- |
| میزبان اندروید | `android/app` | روی گوشی سیم‌کارت‌دار نصب می‌شود و برای تماس و پیامک به سیم‌کارت دسترسی دارد |
| اپ آیفون و آیپد | `ios/Tether` | تماس، پیامک، مخاطبان و سابقهٔ تماس را روی دستگاه اپل در دسترس می‌گذارد |
| کلارک همراه | `android/client` | همین امکانات را روی گوشی دوم اندرویدی فراهم می‌کند |
| کتابخانه‌ها و پروتکل | `android/core`، `ios/TetherCore` و `protocol` | پیاده‌سازی ارتباط، رمزنگاری و پیام‌های مشترک |

میزبان به Android 8 یا بالاتر و پشتیبانی از تبلیغ BLE نیاز دارد. نسخهٔ اندرویدی گوشی دوم به Android 10 یا بالاتر و نسخهٔ اپل به iOS یا iPadOS 16 یا بالاتر نیاز دارد. پشتیبانی از صدای دوطرفه به نسخهٔ اندروید و مدل گوشی میزبان بستگی دارد. [جدول سازگاری اندروید](docs/devices/android-versions.md)

## ساخت نسخهٔ آزمایشی اندروید

JDK 17 و Android SDK Platform 36 را آماده کنید. Android Studio می‌تواند مسیر SDK را در `android/local.properties` تنظیم کند. پروژه Gradle Wrapper دارد و نسخهٔ Gradle را در اولین اجرا دانلود می‌کند.

```bash
git clone https://github.com/ashkan0033/tether.git clark
cd clark
git switch clark/app-branding
cd android
./gradlew :app:assembleDebug :client:assembleDebug
```

در ویندوز از `gradlew.bat :app:assembleDebug :client:assembleDebug` استفاده کنید. خروجی‌های آزمایشی در `android/app/build/outputs/apk/debug/` و `android/client/build/outputs/apk/debug/` قرار می‌گیرند.

## آیفون و راه‌اندازی

بیلد از روی سورس iOS به macOS، Xcode و XcodeGen نیاز دارد. برای راه‌اندازی محیط توسعه و جفت‌سازی، از راهنماهای فنی زیر استفاده کنید. ممکن است بعضی راهنماها هنوز نام Tether را نشان دهند.

- [راهنمای بیلد Android و iOS](docs/BUILD.md)
- [راهنمای اتصال و جفت‌سازی](docs/SETUP.md)
- [راهنمای گوشی دوم اندرویدی](docs/ANDROID-CLIENT.md)
- [گزارش خطا در کلارک](https://github.com/ashkan0033/tether/issues)

## About Clark

Clark connects a SIM-equipped Android phone to an iPhone, iPad, or second Android phone over an encrypted Bluetooth link. It brings calls, SMS, contacts, and call history to the device you carry. The fork is in development and has no published Clark installers yet.

Clark is based on [Tether by amirwmr and contributors](https://github.com/amirwmr/tether). It is available under the [MIT License](LICENSE).
