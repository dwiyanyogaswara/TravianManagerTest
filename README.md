# Travian Farm Assistant v4.8

Android WebView assistant for Travian Farm List automation.

## Features
- Automatic login and auto re-login when the Travian session expires.
- Periodically opens the Farm List page and clicks **Start All Farm Lists**.
- Random interval range can be entered as minimum/maximum minutes (minimum 1 minute) and is saved for the next app launch. Each cycle chooses a random delay within the range.
- Automation runs in an Android Foreground Service, so it can continue when the app is in the background (Home/another app) with a persistent notification.
- Does not parse or enumerate individual Farm Lists.
- WebView has its own scrolling area so the Travian page can be scrolled fully.
- Activity log is shown in the app.
- Log entries are timestamped and older than 12 hours are automatically deleted.
- Only the latest 200 log lines are kept in the on-screen view.
- Log cleanup runs hourly in addition to cleanup on each write.
- Password remains in RAM only while the automation process/service is alive; it is not stored in SharedPreferences.
- Android may still stop the automation after a force-stop or due to device/vendor battery restrictions.

## Build
GitHub Actions workflow: `.github/workflows/main.yml`.

The workflow builds `:app:assembleDebug` and uploads the debug APK as an artifact.


## v3.6
- Foreground notification explicitly shows Farm Assistant AKTIF and next raid run time.
- Main screen shows live countdown to the next run.
- Android 13+ requests notification permission so the foreground notification can be shown.


## v3.8
- Resource Builder dapat mencoba memakai resource dari Hero Inventory ketika resource village tidak cukup untuk upgrade field terendah.
- Kebutuhan resource upgrade dihitung dari halaman upgrade; hanya resource yang kurang yang diminta dari inventory.
- Mendukung item resource Lumber/Clay/Iron/Crop dan dialog jumlah dengan fallback selector.
- Jika inventory atau dialog tidak dikenali, village dilewati dan dicatat di Log Aktivitas.
- VersionCode 18 / versionName 3.8.


## v4.3
- Dropdown durasi 1/6/12/24 jam dihapus.
- Ditambahkan checkbox untuk mengaktifkan/nonaktifkan Farm List.
- Log mencatat jumlah raid yang terdeteksi terkirim setelah Send All Farm.
- Halaman log memiliki tombol HAPUS LOG.
- Tema aplikasi diganti menjadi dark mode.

## v3.9
- Memperbaiki konfirmasi pengiriman Farm List: klik **Send All Farm Lists** memakai event mouse dan diverifikasi dari perubahan jumlah **being raided**.
- Jika klik Send All tidak terkonfirmasi, aplikasi mencoba fallback klik tombol **Start** pada Farm List yang masih siap dikirim.
- Resource Builder sekarang hanya memilih field dengan level **di bawah 10**; field level 10 atau lebih tidak dipilih.
- Deteksi daftar village dibuat lebih fleksibel dan melakukan retry jika daftar belum selesai dirender.
- VersionCode 20 / versionName 4.0.


## v4.3
- Saat background automation aktif dan aplikasi sedang terbuka, WebView yang terlihat di bagian bawah menjadi **LIVE automation WebView** yang sama dengan halaman yang dipakai bot.
- Aksi bot seperti membuka Farm List, berpindah village, membuka halaman resource field, dan membuka Hero Inventory akan terlihat langsung di layar.
- Saat Activity ditutup, service otomatis kembali memakai WebView background miliknya sehingga automation tetap dapat berjalan.
- WebView visible dan service berbagi session/cookie Travian.
- VersionCode 23 / versionName 4.1.

## v4.3
- Memperbaiki error compile `Unresolved reference onVisibleWebViewDetached` pada MainActivity.
- Menambahkan bridge companion untuk memastikan service mengambil alih WebView background saat WebView visible dilepas.


## v4.4
- Urutan siklus diperketat: Farm List harus selesai dikirim dan diverifikasi terlebih dahulu sebelum Resource Builder dimulai.
- Setelah Send All, aplikasi menunggu sampai UI Farm List tidak lagi memiliki tombol Start aktif dan proses pengiriman tidak sedang berjalan.
- Jika Send All tidak terkonfirmasi, fallback Start per Farm List juga ditunggu sampai seluruh pengiriman selesai.
- Resource Builder baru dimulai setelah fase Farm List benar-benar selesai.
- Jika halaman Farm List belum siap saat verifikasi, aplikasi tetap menunggu dan tidak langsung memulai builder.
- VersionCode 24 / versionName 4.4.


## v4.7
- Memperbaiki error compile `Unresolved reference: wrappers` pada verifikasi fallback Farm List.
- Jumlah `.farmListWrapper` sekarang diparsing dari hasil JavaScript sebelum dipakai oleh Kotlin.
- VersionCode 25 / versionName 4.5.

## v4.8

- Live WebView menggunakan User-Agent desktop dan wide viewport.
- Panel aplikasi dapat di-scroll dan daftar village dapat dimuat.
- Setiap village dapat dicentang untuk menentukan target Resource Builder.
- Pilihan village disimpan dan diteruskan ke background service.
- Resource Builder tidak memproses village yang tidak dipilih.


## v4.10.0 UI
- 3 tab utama: Farm Res Builder, Capacity Overview, Log.
- Capacity Overview membaca resource current/capacity per village dari scanner.
- Snapshot dashboard disimpan di SharedPreferences agar tetap terlihat setelah Activity dibuka kembali.


## v4.14.0
Verbose debug tracing added at function entry points. High-frequency UI refresh functions remain Logcat-only to avoid excessive 12-hour log-file growth.
