# 4.14.14

- Resource Builder mengikuti urutan sederhana: DB -> IsChecklist -> LinkVillage -> LinkResource -> upgrade/transfer -> village berikutnya.
- Cycle pertama langsung dimulai tanpa Refresh Village terlebih dahulu.
- Setelah cycle selesai: Next Run/countdown dimulai.
- Refresh Village dijalankan 30 detik setelah countdown dimulai.
- Refresh Village dibatasi maksimal 3 menit dan ditutup paksa jika timeout.
- Jika Next Run tiba saat Refresh Village masih berjalan, cycle menunggu sampai refresh selesai/timeout agar WebView tidak bentrok.
