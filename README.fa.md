# TccBridge

[English](README.md) · **فارسی**

**پلاگین Paper که سرور بازی TCC رو به `site.tccmc.ir` وصل می‌کنه.**

سایت و هاست بازی رو شبکه‌هایی هستن که همیشه هم‌دیگه رو نمی‌بینن — هاست ایرانی ترافیک خارجی رو می‌ندازه. پس پلاگین خودش **به بیرون** زنگ می‌زنه و منتظر نمی‌مونه سایت بهش زنگ بزنه. یعنی نه پورت ورودی باز می‌کنیم، نه کنسول عمومی می‌شه، نه RCON بیرون می‌افته.

---

## چیکار می‌کنه

| جهت | مسیر | کار |
|---|---|---|
| سرور → سایت | `POST /api/mc/ingest` | وضعیت واقعی هر ۲۰ ثانیه: تعداد بازیکن، حداکثر، MOTD، ورژن، TPS، MSPT |
| سایت → سرور | `POST /api/mc/poll` | دستورهای کنسولی که تو صف موندن (مثلاً رنکی که خریداری شده) |
| سرور → سایت | `POST /api/mc/ack` | نتیجه‌ی اجرای هر دستور |
| سرور → سایت | کنسول، متریک، فایل | پنل دولوپر و مالک روی سایت |
| سرور → سایت | `POST /api/mc/stats` | آمار تجمعی بازیکن با PlaceholderAPI |
| سرور → سایت | `POST /api/mc/tiers` | تیرهایی که تو بازی داده می‌شه، آینه می‌شه روی سایت |
| داخل بازی | `/site connect <code>` | ثابت می‌کنه اکانت مال خودته و به سایت وصلش می‌کنه |

سایت هیچ‌وقت داده از خودش درنمی‌آره؛ اگه بریج قطع باشه، پنل‌ها همون رو می‌گن.

## دستورها

| دستور | برای کی | کار |
|---|---|---|
| `/site connect <code>` | بازیکن‌ها | وصل کردن اکانت ماینکرفت به سایت |
| `/site status` | همه | بریج در دسترسه؟ آخرین ارسال کِی بود؟ |
| `/site reload` | `tccbridge.admin` | خوندن دوباره‌ی `config.yml` بدون ری‌استارت |

آلیاس: `/tccweb`

## نصب

1. جار رو بگیر — GitHub Actions با هر پوش بیلدش می‌کنه و می‌ذاره روی ریلیز — و بندازش تو `plugins/`.
2. یه بار سرور رو بالا بیار تا `plugins/TccBridge/config.yml` ساخته بشه.
3. `site-url` رو ست کن و مقدار `MC_INGEST_TOKEN` سایت رو بذار تو `token`.
4. ری‌استارت کن، یا به‌عنوان اپراتور `/site reload` بزن.

```yaml
site-url: "https://site.tccmc.ir"
token: "<MC_INGEST_TOKEN از .env سایت>"
push-interval-seconds: 20
poll-interval-seconds: 10
console-push-seconds: 5
tier-push-seconds: 60
stats-push-seconds: 900
stats-players: 120
```

## بیلد از سورس

JDK 21 لازمه.

```bash
gradle build          # جار می‌آد تو build/libs/TccBridge-<version>.jar
```

توی ریپو wrapper نداریم؛ CI خودش Gradle رو ست می‌کنه. اگه wrapper می‌خوای، یه بار `gradle wrapper` بزن و کامیتش کن.

## داخلش چی هست

```
src/main/java/ir/tcc/bridge/
  TccBridge.java    نقطه‌ی ورود، دستورها، زمان‌بندی
  StatsReader.java  آمار بازیکن از PlaceholderAPI
  TierReader.java   خوندن سیستم تیر سرور و آینه‌کردنش
  LogTailer.java    دنبال‌کردن latest.log برای پنل کنسول
  Fs.java           عملیات فایل سندباکس‌شده برای پنل دولوپر
  Json.java         نوشتن JSON بدون هیچ کتابخانه
```

همه‌چیز Java خالی + Paper API ـه؛ هیچ کتابخانه‌ای shade نمی‌شه، پس جار سبکه و با پلاگین‌های دیگه تعارض نداره.

## پیش‌نیاز

- Paper 1.21.8 (api-version 1.21)، Java 21
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) برای آمار بازیکن (اختیاری ولی توصیه می‌شه)

## امنیت

- پلاگین فقط **بیرون** درخواست می‌زنه؛ هیچ پورتی برای سایت باز نمی‌کنه.
- هر درخواست هدر `X-TCC-Token` داره؛ `config.yml` رو از بکاپ عمومی دور نگه دار و هیچ‌وقت کامیتش نکن.
- کنسول و فایل فقط از پنل‌های تیم در دسترسه، که خودشون مجوز چک می‌کنن و همه‌چی رو تو Audit ثبت می‌کنن.

مشکلی دیدی؟ خصوصی بگو — [SECURITY.md](SECURITY.md)

## لایسنس

[MIT](LICENSE) © TCC Minecraft
