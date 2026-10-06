# Changelog 4.14.13

- Menyederhanakan alur Resource Builder menjadi DB -> IsChecklist -> LinkVillage -> LinkResource -> cek upgrade -> Upgrade atau openResourceTransfer -> Transfer selected -> Upgrade -> village berikutnya.
- Resource Builder tidak lagi melakukan rediscovery field resource untuk memilih target; target sepenuhnya berasal dari Village Data (ID, MinLvl, LinkResource).
- Village yang tidak dicentang tidak dimasukkan ke loop Resource Builder.
- Village dengan MinLvl >= 10 tetap dihapus saat Auto Refresh Village sehingga tidak ikut loop berikutnya.
- Tidak menggunakan nama tipe resource (Wood/Iron/Clay/Crop) untuk menentukan target.
