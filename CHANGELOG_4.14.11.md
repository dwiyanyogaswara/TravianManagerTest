# Changelog 4.14.11

- Resource Builder tidak lagi bergantung pada nama tipe resource; target disimpan berdasarkan field `id`, level terendah, dan URL `build.php` yang sesuai.
- Village dengan `MinLvl >= 10` otomatis dihapus dari Village Data setelah Refresh Village agar loop Resource Builder lebih ringan.
- Countdown berikutnya menjadi acuan jadwal Refresh Village: refresh berjalan 1 menit setelah countdown dimulai.
- Refresh Village memiliki batas maksimum 2 menit dan ditutup paksa jika melewati batas.
- Siklus menunggu Refresh Village benar-benar ditutup sebelum Farm List/Resource Builder berjalan.
- Resource Builder membuka URL target tersimpan secara langsung dengan konteks `newdid`, menghindari pemeriksaan URL `dorf1.php` yang dapat kehilangan parameter village.
- Saat resource upgrade kurang, Builder mencoba elemen `openResourceTransfer` lalu tombol `Transfer selected` sebelum kembali ke halaman upgrade.
