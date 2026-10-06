# Changelog 4.14.12

- Memperbaiki error kompilasi Kotlin pada `delayedVillageRefreshRunnable`: Runnable tidak lagi mereferensikan dirinya sendiri saat inisialisasi property.
- Logika Refresh Village tetap berjalan 1 menit setelah countdown dimulai.
- Target Resource Builder tetap hanya menyimpan ID field, level, dan LinkResource.
- Village dengan MinLvl >= 10 tetap dikeluarkan dari Village Data agar loop Resource Builder lebih ringan.
